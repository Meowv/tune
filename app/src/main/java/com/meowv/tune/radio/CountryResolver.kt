package com.meowv.tune.radio

import android.content.Context
import android.telephony.TelephonyManager
import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.CountryDirectory
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.ScopeMode
import java.util.Locale

class CountryResolver(
    private val networkCountry: () -> String?,
    private val localeCountry: () -> String?
) {
    constructor(context: Context) : this(
        networkCountry = { context.applicationContext.getSystemService(TelephonyManager::class.java)?.networkCountryIso },
        localeCountry = { Locale.getDefault().country }
    )

    suspend fun candidates(scope: RadioScope, directory: CountryDirectory): List<ResolvedRadioScope> {
        if (scope.mode != ScopeMode.AUTO) return listOf(ResolvedRadioScope(scope, scope.countryCode))
        val hints = listOf(CountryCode.parse(networkHint()), CountryCode.parse(localeCountry()))
            .filterNotNull().distinct()
        if (hints.isEmpty()) return listOf(ResolvedRadioScope(scope, null))
        val available = directory.countries().mapTo(hashSetOf()) { it.code }
        return hints.filter { it in available }.map { ResolvedRadioScope(scope, it) } +
            ResolvedRadioScope(scope, null)
    }

    private fun networkHint(): String? = try {
        networkCountry()
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}
