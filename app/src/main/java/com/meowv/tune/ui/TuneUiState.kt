package com.meowv.tune.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.meowv.tune.R
import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ScopeMode

enum class TunePage(@StringRes val label: Int) {
    Radio(R.string.menu_radio), Music(R.string.menu_music), Settings(R.string.menu_settings)
}

enum class DemoScope(@StringRes val label: Int, @StringRes val optionLabel: Int = label, val scope: RadioScope) {
    Auto(R.string.scope_china, R.string.scope_auto_china, RadioScope()),
    Global(R.string.scope_global, scope = RadioScope(ScopeMode.GLOBAL)),
    China(R.string.scope_china, scope = demoCountry("CN")),
    Japan(R.string.scope_japan, scope = demoCountry("JP")),
    UnitedStates(R.string.scope_us, scope = demoCountry("US")),
    UnitedKingdom(R.string.scope_uk, scope = demoCountry("GB"))
}

private fun demoCountry(code: String) = RadioScope(ScopeMode.COUNTRY, requireNotNull(CountryCode.parse(code)))

internal data class DemoStation(val id: String, @StringRes val name: Int, val color: Color)

internal val DemoStations = listOf(
    DemoStation("CN", R.string.station_city, Color(0xFF54468B)),
    DemoStation("MG", R.string.station_morning, Color(0xFF664D99)),
    DemoStation("YE", R.string.station_night, Color(0xFF3A6985)),
    DemoStation("SH", R.string.station_mountain, Color(0xFF795E93)),
    DemoStation("MT", R.string.station_slow, Color(0xFF487682)),
    DemoStation("QK", R.string.station_sky, Color(0xFF5F478D))
)

internal fun isCarWindow(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 600 && heightDp >= 360 &&
        (widthDp >= 840 && widthDp > heightDp || widthDp / heightDp >= 1.35f)
