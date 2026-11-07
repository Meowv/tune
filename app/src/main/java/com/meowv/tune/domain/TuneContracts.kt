package com.meowv.tune.domain

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RadioRepository : CountryDirectory {
    suspend fun stationsForCountry(country: CountryCode, offset: Int, limit: Int): StationPage
    suspend fun randomGlobalStations(limit: Int): List<Station>
}

class QueueSelection internal constructor(val station: Station)

interface RadioQueue {
    val canPrevious: StateFlow<Boolean>

    fun updateDirectory(scope: ResolvedRadioScope, page: StationPage)
    fun clearScope()
    fun select(scope: ResolvedRadioScope, uuid: UUID): QueueSelection?

    // cursor 是已考虑的台站，播放失败重试时可以传入失败台站的 UUID。
    suspend fun next(scope: ResolvedRadioScope, cursor: UUID?): QueueSelection?
    fun previous(scope: ResolvedRadioScope): QueueSelection?

    // 只有服务确认开始播放后才提交选择，过期确认返回 false。
    fun recordSuccessfulPlayback(selection: QueueSelection): Boolean
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

fun interface CountryDirectory {
    suspend fun countries(): List<RadioCountry>
}
