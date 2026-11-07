package com.meowv.tune.domain

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RadioRepository {
    suspend fun stationsForCountry(country: CountryCode, offset: Int, limit: Int): List<Station>
    suspend fun randomGlobalStations(limit: Int): List<Station>
}

interface RadioQueue {
    suspend fun next(scope: RadioScope, current: UUID?): Station?
    suspend fun previous(scope: RadioScope): Station?
}

sealed interface PlayerCommand {
    data class Play(val station: Station) : PlayerCommand
    data object TogglePause : PlayerCommand
    data object Next : PlayerCommand
    data object Previous : PlayerCommand
    data object Stop : PlayerCommand
}

interface PlayerController {
    val state: StateFlow<PlayerUiState>

    // 实现由播放服务持有，并在服务内串行执行命令。
    suspend fun dispatch(command: PlayerCommand)
}

enum class LayoutMode { AUTO, PHONE, CAR }

data class TuneSettings(
    val scope: RadioScope = RadioScope(),
    val layout: LayoutMode = LayoutMode.AUTO,
    val autoPlay: Boolean = false,
    val wifiOnly: Boolean = false,
    val dataWarning: Boolean = true,
    val pauseOnDisconnect: Boolean = true,
    val lastSuccessfulStation: UUID? = null
)

interface SettingsRepository {
    val settings: Flow<TuneSettings>

    // 变换在持久化事务内读取最新状态，避免并发写入覆盖其他字段。
    suspend fun update(transform: (TuneSettings) -> TuneSettings)

    // 仅由确认播放成功的服务调用。
    suspend fun recordSuccessfulStation(uuid: UUID)
}
