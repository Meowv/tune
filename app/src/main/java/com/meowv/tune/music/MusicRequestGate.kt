package com.meowv.tune.music

import com.meowv.tune.domain.music.MusicException
import com.meowv.tune.domain.music.MusicFailure

// 使用单调时钟共享冷却窗口，用户重试不会延长或绕过既有窗口。
internal class MusicRequestGate(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var blockedUntil = Long.MIN_VALUE
    private var reason = MusicFailure.RATE_LIMITED

    @Synchronized fun check() {
        val now = nowMillis()
        if (now >= blockedUntil) return
        val delta = try { Math.subtractExact(blockedUntil, now) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        throw MusicException(reason, delta / 1000 + if (delta % 1000 > 0) 1 else 0)
    }

    @Synchronized fun block(seconds: Long?, failure: MusicFailure = MusicFailure.RATE_LIMITED) {
        val delay = try { Math.multiplyExact((seconds ?: 60).coerceAtLeast(1), 1000) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        val deadline = try { Math.addExact(nowMillis(), delay) } catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (deadline >= blockedUntil) { blockedUntil = deadline; reason = failure }
    }
}
