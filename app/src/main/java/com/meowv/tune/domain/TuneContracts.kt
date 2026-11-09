package com.meowv.tune.domain

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RadioRepository : CountryDirectory {
    suspend fun stationsForCountry(country: CountryCode, offset: Int, limit: Int): StationPage
    suspend fun randomGlobalStations(limit: Int): List<Station>

    // 启动恢复只按 UUID 重新解析元数据，不建立可浏览历史。
    suspend fun stationByUuid(uuid: UUID): Station?
}

class QueueSelection internal constructor(val station: Station)

interface RadioQueue {
    val canPrevious: StateFlow<Boolean>
    val canNext: StateFlow<Boolean>
    val activeScope: StateFlow<ResolvedRadioScope?>

    fun updateDirectory(scope: ResolvedRadioScope, page: StationPage)
    fun clearScope()
    fun select(scope: ResolvedRadioScope, uuid: UUID): QueueSelection?

    // 恢复时该台可能不在当前目录页内，按已确认元数据直接定位。
    fun select(scope: ResolvedRadioScope, station: Station): QueueSelection? = select(scope, station.uuid)

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
    data object CancelPending : PlayerCommand
    data class Resume(val uuid: UUID) : PlayerCommand
    data object Stop : PlayerCommand
}

interface PlayerController {
    val state: StateFlow<PlayerUiState>

    // 实现由播放服务持有，并在服务内串行执行命令。
    suspend fun dispatch(command: PlayerCommand)
}

data class TuneSettings(
    val scope: RadioScope = RadioScope(),
    val autoPlay: Boolean = false,
    val lastSuccessfulStation: UUID? = null,
    val displayMode: RadioDisplayMode = RadioDisplayMode.LIST,
    val playbackOrder: PlaybackOrder = PlaybackOrder.SEQUENTIAL
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
