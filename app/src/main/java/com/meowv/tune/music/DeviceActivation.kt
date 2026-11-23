package com.meowv.tune.music

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class MusicDevicePhase { CHECKING, OFFLINE, PENDING, ACTIVE, REVOKED, REAPPROVAL_REQUIRED, UNAVAILABLE }

internal data class MusicDeviceState(
    val phase: MusicDevicePhase = MusicDevicePhase.CHECKING,
    val deviceCode: String? = null,
    val keyFingerprint: String? = null
)

internal class DeviceActivation(
    private val preferences: DataStore<Preferences>,
    private val client: MusicActivationClient,
    private val androidId: () -> String?
) {
    private val mutableState = MutableStateFlow(MusicDeviceState())
    val state = mutableState.asStateFlow()
    private val foreground = Mutex()
    private val storage = Mutex()
    private val rejected = Channel<Unit>(Channel.CONFLATED)
    private val credential = AtomicReference<Credential?>()
    private val serviceUrl = client.baseUrl.toString()

    suspend fun readToken(): String? {
        credential.get()?.let { return it.usableToken }
        return withContext(Dispatchers.IO) {
            storage.withLock {
                credential.get()?.let { return@withLock it.usableToken }
                val saved = try {
                    preferences.data.first()
                } catch (_: IOException) {
                    return@withLock null
                }
                if (saved[ServiceUrl] != serviceUrl) return@withLock null
                val id = derivedId() ?: saved[InstallationId] ?: return@withLock null
                val ciphertext = saved[EncryptedToken] ?: return@withLock null
                decode(id, ciphertext)?.also { credential.set(Credential(it)) }
            }
        }
    }

    fun onUnauthorized(value: String) {
        val current = credential.get() ?: return
        if (current.usableToken == value && credential.compareAndSet(current, Credential(value, rejected = true))) {
            mutableState.update {
                if (it.phase == MusicDevicePhase.ACTIVE) it.copy(phase = MusicDevicePhase.CHECKING) else it
            }
            rejected.trySend(Unit)
        }
    }

    suspend fun runWhileForeground() = withContext(Dispatchers.IO) {
        foreground.withLock {
            var attempts = 0
            while (currentCoroutineContext().isActive) {
                val previous = state.value.phase
                val retryAfter = try {
                    synchronize()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    setConnectionFailure(MusicDevicePhase.OFFLINE)
                    null
                } catch (_: Exception) {
                    setConnectionFailure(MusicDevicePhase.UNAVAILABLE)
                    null
                }

                when (state.value.phase) {
                    MusicDevicePhase.REVOKED, MusicDevicePhase.REAPPROVAL_REQUIRED -> attempts = Backoff.lastIndex
                    MusicDevicePhase.ACTIVE -> {
                        // 有效凭据不轮询；回前台和业务请求 401 时重新查状态。
                        if (credential.get()?.usableToken != null) {
                            rejected.receive()
                            attempts = 0
                            continue
                        }
                    }
                    MusicDevicePhase.PENDING -> if (previous != MusicDevicePhase.PENDING) attempts = 0
                    else -> Unit
                }

                val delaySeconds = maxOf(retryAfter?.coerceAtMost(3600) ?: 0, Backoff[attempts])
                if (attempts < Backoff.lastIndex) attempts++
                // 合并请求失败，不让一批 401 打断服务器要求的退避。
                withTimeoutOrNull(delaySeconds * 1_000 + Random.nextLong(1_001)) {
                    while (true) rejected.receive()
                }
            }
        }
    }

    private suspend fun synchronize(): Long? {
        var identity = storage.withLock {
            val saved = preferences.data.first()
            val derived = derivedId()
            val savedId = saved[InstallationId]
            if (derived != null && savedId != null && derived != savedId) {
                MusicDeviceKeys.delete(savedId)
                return@withLock createIdentity()
            }
            val id = derived ?: savedId
            if (id == null) return@withLock createIdentity()
            // 只有已有登记且服务地址变化时才换钥；清数据后 ServiceUrl 也为空，应复用 Keystore。
            if (savedId != null && saved[ServiceUrl] != serviceUrl) {
                MusicDeviceKeys.delete(id)
                return@withLock createIdentity()
            }
            if (saved[Revoked] == true)
                setPhase(MusicDevicePhase.REVOKED)
            val key = MusicDeviceKeys.publicKey(id)
            if (key != null) Identity(id, key, saved[Registered] == true)
            else createIdentity()
        }

        val deviceCode = showDeviceCode(identity)
        var reply = if (identity.registered) client.status(identity.id) else register(identity)
        if (reply.code == 404 && reply.errorCode() == "device_not_found") {
            storage.withLock {
                credential.set(null)
                preferences.edit {
                    it.remove(EncryptedToken)
                    it.remove(Registered)
                }
            }
            setPhase(MusicDevicePhase.CHECKING)
            reply = register(identity)
        }

        if (reply.code == 409 && reply.errorCode() == "device_reapproval_required") {
            credential.set(null)
            setPhase(MusicDevicePhase.REAPPROVAL_REQUIRED)
            return reply.retryAfterSeconds
        }
        if (reply.code !in setOf(200, 202)) {
            setConnectionFailure(MusicDevicePhase.UNAVAILABLE)
            return reply.retryAfterSeconds
        }

        check(reply.text("installationId") == identity.id && reply.text("deviceCode") == deviceCode)
        when (reply.text("status")) {
            "pending" -> storage.withLock {
                credential.set(null)
                preferences.edit {
                    it[Registered] = true
                    it.remove(Revoked)
                    it.remove(EncryptedToken)
                }
                setPhase(MusicDevicePhase.PENDING)
            }
            "active" -> {
                // register/status 都可能返回 active 与密文；202 无密文时等下一次 status。
                val ciphertext = reply.text("encryptedToken")
                if (ciphertext == null && reply.code == 202) {
                    preferences.edit { it[Registered] = true }
                    return null
                }
                val clear = ciphertext?.let { decode(identity.id, it) }
                if (clear == null && identity.registered) {
                    // 本地密钥与服务端记录失配时，下一轮重新登记以取得明确的换钥状态。
                    storage.withLock {
                        credential.set(null)
                        preferences.edit {
                            it.remove(Registered)
                            it.remove(EncryptedToken)
                        }
                    }
                    setPhase(MusicDevicePhase.CHECKING)
                    return null
                }
                checkNotNull(clear)
                storage.withLock {
                    preferences.edit {
                        it[Registered] = true
                        it.remove(Revoked)
                        it[EncryptedToken] = checkNotNull(ciphertext)
                    }
                    val installed = credential.updateAndGet { current ->
                        if (current?.token == clear) current else Credential(clear)
                    }
                    setPhase(if (installed?.rejected == true) MusicDevicePhase.UNAVAILABLE else MusicDevicePhase.ACTIVE)
                }
            }
            "revoked" -> storage.withLock {
                credential.set(null)
                preferences.edit {
                    it[Registered] = true
                    it[Revoked] = true
                    it.remove(EncryptedToken)
                }
                setPhase(MusicDevicePhase.REVOKED)
            }
            else -> error("Unknown device status")
        }
        return null
    }

    private suspend fun register(identity: Identity) = client.register(
        identity.id, identity.key, label(Build.MODEL, 80), label(Build.VERSION.RELEASE, 32)
    )

    private fun showDeviceCode(identity: Identity): String {
        val deviceCode = MusicDeviceIdentity.deviceCode(identity.id)
        val fingerprint = MusicDeviceIdentity.keyFingerprint(identity.key)
        mutableState.update { it.copy(deviceCode = deviceCode, keyFingerprint = fingerprint) }
        return deviceCode
    }

    private suspend fun createIdentity(): Identity {
        credential.set(null)
        mutableState.value = MusicDeviceState()
        val id = derivedId() ?: UUID.randomUUID().toString().replace("-", "")
        preferences.edit {
            it.clear()
            it[InstallationId] = id
            it[ServiceUrl] = serviceUrl
        }
        val key = MusicDeviceKeys.ensure(id)
        return Identity(id, key, registered = false)
    }

    private fun derivedId(): String? = androidId()
        ?.takeIf(String::isNotBlank)
        ?.let(MusicDeviceIdentity::installationId)

    private fun decode(id: String, ciphertext: String): String? = try {
        if (ciphertext.length != 342) null
        else {
            val bytes = MusicDeviceKeys.decrypt(id, Base64.getUrlDecoder().decode(ciphertext))
            try {
                bytes.toString(Charsets.US_ASCII).takeIf(TokenPattern::matches)
            } finally {
                bytes.fill(0)
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ProviderException) {
        null
    }

    private fun label(value: String, max: Int) =
        value.filterNot(Char::isISOControl).trim().take(max).ifBlank { "?" }

    private fun setConnectionFailure(phase: MusicDevicePhase) {
        if (credential.get()?.usableToken == null && state.value.phase !in
            setOf(MusicDevicePhase.REVOKED, MusicDevicePhase.REAPPROVAL_REQUIRED)
        ) setPhase(phase)
    }

    private fun setPhase(phase: MusicDevicePhase) {
        mutableState.value = state.value.copy(phase = phase)
    }

    private class Credential(val token: String, val rejected: Boolean = false) {
        val usableToken: String? get() = token.takeUnless { rejected }
    }

    private class Identity(val id: String, val key: ByteArray, val registered: Boolean)

    private companion object {
        val ServiceUrl = stringPreferencesKey("service_url")
        val InstallationId = stringPreferencesKey("installation_id")
        val Registered = booleanPreferencesKey("registered")
        val EncryptedToken = stringPreferencesKey("encrypted_token")
        val Revoked = booleanPreferencesKey("revoked")
        val Backoff = longArrayOf(5, 15, 60)
        val TokenPattern = Regex("mvm_[a-zA-Z0-9_-]{43}")
    }
}
