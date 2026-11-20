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

internal enum class MusicDevicePhase { CHECKING, OFFLINE, PENDING, ACTIVE, REVOKED, UNAVAILABLE }

internal data class MusicDeviceState(
    val phase: MusicDevicePhase = MusicDevicePhase.CHECKING,
    val shortCode: String? = null
)

internal class DeviceActivation(
    private val preferences: DataStore<Preferences>,
    private val client: MusicActivationClient
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
                val id = saved[InstallationId] ?: return@withLock null
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
                    MusicDevicePhase.REVOKED -> attempts = Backoff.lastIndex
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
            val id = saved[InstallationId]
            if (saved[ServiceUrl] != serviceUrl) {
                id?.let(MusicDeviceKeys::delete)
                return@withLock createIdentity()
            }
            if (saved[Revoked] == true)
                mutableState.value = MusicDeviceState(MusicDevicePhase.REVOKED, saved[ShortCode])
            val key = id?.let(MusicDeviceKeys::publicKey)
            if (id != null && key != null) Identity(id, key, saved[Registered] == true)
            else createIdentity()
        }

        var shortCode = showShortCode(identity)
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

        if (reply.code == 410 && reply.errorCode() == "device_removed") {
            identity = storage.withLock {
                MusicDeviceKeys.delete(identity.id)
                createIdentity()
            }
            shortCode = showShortCode(identity)
            reply = register(identity)
        }

        if (reply.code !in setOf(200, 202)) {
            if (reply.code == 409 && reply.errorCode() == "device_code_conflict") {
                storage.withLock {
                    MusicDeviceKeys.delete(identity.id)
                    createIdentity()
                }
            }
            setConnectionFailure(MusicDevicePhase.UNAVAILABLE)
            return reply.retryAfterSeconds
        }

        check(reply.text("installationId") == identity.id && reply.text("shortCode") == shortCode)
        when (reply.text("status")) {
            "pending" -> storage.withLock {
                credential.set(null)
                preferences.edit {
                    it[Registered] = true
                    it.remove(Revoked)
                    it.remove(ShortCode)
                    it.remove(EncryptedToken)
                }
                setPhase(MusicDevicePhase.PENDING)
            }
            "active" -> {
                // 注册重试可返回 active，只有状态接口提供密文。
                val ciphertext = reply.text("encryptedToken")
                if (ciphertext == null && reply.code == 202) {
                    preferences.edit { it[Registered] = true }
                    return null
                }
                val clear = ciphertext?.let { decode(identity.id, it) }
                checkNotNull(clear)
                storage.withLock {
                    preferences.edit {
                        it[Registered] = true
                        it.remove(Revoked)
                        it.remove(ShortCode)
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
                    it[ShortCode] = shortCode
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

    private fun showShortCode(identity: Identity): String {
        val shortCode = MusicDeviceKeys.shortCode(identity.key)
        if (state.value.shortCode != shortCode) mutableState.value = MusicDeviceState(shortCode = shortCode)
        return shortCode
    }

    private suspend fun createIdentity(): Identity {
        credential.set(null)
        mutableState.value = MusicDeviceState()
        val id = UUID.randomUUID().toString().replace("-", "")
        preferences.edit {
            it.clear()
            it[InstallationId] = id
            it[ServiceUrl] = serviceUrl
        }
        val key = MusicDeviceKeys.generate(id)
        return Identity(id, key, registered = false)
    }

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
        if (credential.get()?.usableToken == null && state.value.phase != MusicDevicePhase.REVOKED) setPhase(phase)
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
        val ShortCode = stringPreferencesKey("short_code")
        val Backoff = longArrayOf(5, 15, 60)
        val TokenPattern = Regex("mvm_[a-zA-Z0-9_-]{43}")
    }
}
