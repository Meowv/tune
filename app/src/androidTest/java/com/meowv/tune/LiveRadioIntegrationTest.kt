package com.meowv.tune

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowv.tune.domain.CountryCode
import com.meowv.tune.domain.PlaybackOrder
import com.meowv.tune.domain.PlaybackPhase
import com.meowv.tune.domain.PlayerCommand
import com.meowv.tune.domain.RadioScope
import com.meowv.tune.domain.ResolvedRadioScope
import com.meowv.tune.domain.ScopeMode
import com.meowv.tune.domain.Station
import com.meowv.tune.domain.TuneSettings
import com.meowv.tune.radio.RadioViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T26 公网联调。仅在 `-e liveRadio true` 时执行，避免污染常规回归门禁。
 * 输出以 `LIVE ` 开头的行，供脚本汇总成功率与起播耗时。
 */
@RunWith(AndroidJUnit4::class)
class LiveRadioIntegrationTest {
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TuneTestApplication
    private val connection get() = app.playerController

    @Test
    fun liveClosureAndMetrics() = runBlocking {
        assumeTrue("需要 -e liveRadio true", app.liveRadio)
        val batch = InstrumentationRegistry.getArguments().getString("batch")?.toIntOrNull() ?: 0
        val batchSize = InstrumentationRegistry.getArguments().getString("batchSize")?.toIntOrNull() ?: 10
        app.userSettings.update {
            TuneSettings(
                scope = RadioScope(ScopeMode.COUNTRY, country("DE")),
                playbackOrder = PlaybackOrder.SEQUENTIAL
            )
        }

        if (batch == 0) {
            val coldMs = measureFirstScreen(clearCache = true)
            val cachedMs = measureFirstScreen(clearCache = false)
            println("LIVE firstScreen coldMs=$coldMs cachedMs=$cachedMs")
        }

        val all = collectStations()
        val slice = all.drop(batch * batchSize).take(batchSize)
        println("LIVE batch=$batch size=${slice.size} total=${all.size}")
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(10_000) { connection.connected.first { it } }
            for (station in slice) {
                val sample = withTimeoutOrNull(STEP_TIMEOUT_MS) { sample(station) }
                println(
                    "LIVE station=${station.uuid} cc=${station.countryCode?.value} codec=${station.codec} " +
                        "reachable=${sample?.reachable} ok=${sample?.ok} ms=${sample?.ms} error=${sample?.error}"
                )
                withTimeoutOrNull(5_000) {
                    connection.dispatch(PlayerCommand.Stop)
                    connection.state.first { it.phase == PlaybackPhase.IDLE }
                }
            }
        }
        println("LIVE batch done=$batch")
    }

    private suspend fun sample(station: Station): Sample {
        val cc = station.countryCode
        val scope = if (cc == null) {
            ResolvedRadioScope(RadioScope(ScopeMode.GLOBAL), null)
        } else {
            ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, cc), cc)
        }
        app.radioQueue.clearScope()
        app.radioQueue.updateDirectory(scope, com.meowv.tune.domain.StationPage(listOf(station)))
        val reachable = probe(station)
        val started = System.nanoTime()
        connection.dispatch(PlayerCommand.Play(station))
        val terminal = runCatching {
            withTimeout(PLAY_TIMEOUT_MS) {
                connection.state.first {
                    it.station?.uuid == station.uuid &&
                        (it.phase == PlaybackPhase.PLAYING || it.phase == PlaybackPhase.ERROR)
                }
            }
        }.getOrNull()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return Sample(reachable, terminal?.phase == PlaybackPhase.PLAYING, elapsed, terminal?.error?.name)
    }

    @Test
    fun liveQueueRulesOnRealData() = runBlocking {
        println("LIVE args=" + InstrumentationRegistry.getArguments().keySet() + " live=" + app.liveRadio)
        assumeTrue("需要 -e liveRadio true", app.liveRadio)
        app.userSettings.update {
            TuneSettings(
                scope = RadioScope(ScopeMode.COUNTRY, country("DE")),
                playbackOrder = PlaybackOrder.SEQUENTIAL
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(10_000) { connection.connected.first { it } }
            val scope = withTimeout(15_000) { app.radioQueue.activeScope.first { it != null } }!!
            val page = runCatching { app.radioRepository.stationsForCountry(scope.country!!, 0, 30) }
                .onFailure { println("LIVE repo error=$it") }
                .getOrNull()
            println("LIVE page size=${page?.stations?.size} next=${page?.nextOffset}")
            assumeTrue("目录不可用", page != null)
            val stations = page!!.stations
            app.radioQueue.clearScope()
            app.radioQueue.updateDirectory(scope, page!!)
            val playable = stations.firstOrNull { probe(it) }
            assumeTrue("无可用公网样本", playable != null)
            connection.dispatch(PlayerCommand.Play(playable!!))
            withTimeout(PLAY_TIMEOUT_MS) {
                connection.state.first { it.phase == PlaybackPhase.PLAYING && it.station?.uuid == playable.uuid }
            }
            println("LIVE queue start=${playable.uuid}")

            var advanced = 0
            repeat(5) {
                val before = connection.state.value.station?.uuid
                connection.dispatch(PlayerCommand.Next)
                val next = runCatching {
                    withTimeout(PLAY_TIMEOUT_MS) {
                        connection.state.first { it.phase == PlaybackPhase.PLAYING && it.station?.uuid != before }
                    }
                }.getOrNull()
                if (next != null) {
                    assertEquals("下一台必须留在所选国家", scope.country, next.station?.countryCode)
                    advanced++
                    println("LIVE queue next=${next.station?.uuid} cc=${next.station?.countryCode?.value}")
                } else {
                    println("LIVE queue next failed state=${connection.state.value}")
                }
            }
            assertTrue("连续下一台应至少成功一次", advanced >= 1)

            if (app.radioQueue.canPrevious.value) {
                val before = connection.state.value.station?.uuid
                connection.dispatch(PlayerCommand.Previous)
                val back = runCatching {
                    withTimeout(PLAY_TIMEOUT_MS) {
                        connection.state.first { it.phase == PlaybackPhase.PLAYING && it.station?.uuid != before }
                    }
                }.getOrNull()
                println("LIVE queue previous=${back?.station?.uuid}")
                // 等上一台真正切换完成再进入换范围步骤。
                delay(500)
            }

            // 换范围不打断当前流，下一次下一台使用新范围。
            val playing = connection.state.value.station
            val us = country("US")
            val newScope = ResolvedRadioScope(RadioScope(ScopeMode.COUNTRY, us), us)
            app.userSettings.update { it.copy(scope = RadioScope(ScopeMode.COUNTRY, us)) }
            app.radioQueue.clearScope()
            app.radioQueue.updateDirectory(newScope, app.radioRepository.stationsForCountry(us, 0, 30))
            delay(300)
            assertEquals("换范围不应停止当前台", playing?.uuid, connection.state.value.station?.uuid)
            assertEquals(PlaybackPhase.PLAYING, connection.state.value.phase)
            println("LIVE scopeChange kept=${playing?.uuid}")

            // 不限区域随机
            val global = ResolvedRadioScope(RadioScope(ScopeMode.GLOBAL), null)
            app.radioQueue.clearScope()
            app.radioQueue.updateDirectory(global, com.meowv.tune.domain.StationPage(app.radioRepository.randomGlobalStations(30)))
            assertTrue("全球随机应有候选", app.radioQueue.canNext.value)
            println("LIVE global candidates=${app.radioQueue.canNext.value}")
            connection.dispatch(PlayerCommand.Stop)
        }
    }

    private data class Sample(val reachable: Boolean, val ok: Boolean, val ms: Long, val error: String?)

    private fun report(samples: List<Sample>) {
        val reachable = samples.filter { it.reachable }
        val success = reachable.filter { it.ok }
        val times = success.map { it.ms }.sorted()
        val rate = if (reachable.isEmpty()) 0.0 else success.size * 100.0 / reachable.size
        println("LIVE summary samples=${samples.size} reachable=${reachable.size} success=${success.size} rate=${"%.1f".format(rate)}%")
        println("LIVE latency p50=${percentile(times, 0.50)} p95=${percentile(times, 0.95)}")
        val reasons = samples.filter { !it.ok }.groupingBy { it.error ?: "UNKNOWN" }.eachCount()
        println("LIVE failures=$reasons")
        val unreachable = samples.filter { !it.reachable }
        println("LIVE unreachable=${unreachable.size} reasons=${unreachable.groupingBy { it.error ?: "UNREACHABLE" }.eachCount()}")
        assertTrue("有效样本不少于 50", samples.size >= 50)
        assertTrue("有效且可达源成功率应不低于 95%（实际 ${"%.1f".format(rate)}%）", rate >= 95.0)
    }

    private fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return -1
        val index = ((values.size - 1) * p).toInt()
        return values[index]
    }

    private suspend fun measureFirstScreen(clearCache: Boolean): Long {
        if (clearCache) app.directoryCache.clear()
        val started = System.nanoTime()
        var elapsed = -1L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: RadioViewModel
            scenario.onActivity { model = ViewModelProvider(it)[RadioViewModel::class] }
            withTimeout(20_000) { model.radioState.first { !it.isLoading && it.stations.isNotEmpty() } }
            elapsed = (System.nanoTime() - started) / 1_000_000
        }
        return elapsed
    }

    private suspend fun collectStations(): List<Station> {
        val codes = listOf("DE", "US", "FR", "GB", "JP", "BR", "NL", "IN", "IT", "ES")
        val seen = LinkedHashMap<String, Station>()
        val hls = mutableListOf<Station>()
        for (code in codes) {
            val page = runCatching { app.radioRepository.stationsForCountry(country(code), 0, 30) }.getOrNull()
                ?: continue
            page.stations.filter { it.isHls }.forEach { if (hls.size < 3) hls += it }
            page.stations.take(PER_COUNTRY).forEach { seen.putIfAbsent(it.uuid.toString(), it) }
        }
        // HLS 台站稀少，优先纳入样本以满足协议覆盖。
        val ordered = LinkedHashMap<String, Station>()
        hls.forEach { ordered[it.uuid.toString()] = it }
        seen.forEach { (k, v) -> ordered.putIfAbsent(k, v) }
        return ordered.values.take(TARGET_SAMPLES)
    }

    private fun probe(station: Station): Boolean {
        val url = station.resolvedStreamUrl ?: station.streamUrl ?: run {
            println("LIVE probe no-url ${station.uuid}")
            return false
        }
        val request = Request.Builder().url(url).header("Range", "bytes=0-2047").build()
        return runCatching {
            app.mediaHttp.newCall(request).execute().use { response ->
                val ok = response.isSuccessful || response.code in 300..399
                if (!ok) println("LIVE probe ${response.code} $url")
                ok
            }
        }.onFailure { println("LIVE probe error ${it::class.java.simpleName}: ${it.message} $url") }
            .getOrDefault(false)
    }

    private fun country(code: String): CountryCode = requireNotNull(CountryCode.parse(code))

    private companion object {
        const val TARGET_SAMPLES = 50
        const val PER_COUNTRY = 5
        const val PLAY_TIMEOUT_MS = 20_000L
        const val STEP_TIMEOUT_MS = 35_000L
        val MEDIA_TIMEOUT = TimeUnit.SECONDS.toMillis(6)
    }
}
