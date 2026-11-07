package com.meowv.tune.playback

import com.meowv.tune.domain.TuneError
import com.meowv.tune.domain.TuneFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryPolicyTest {
    private val policy = PlaybackRetryPolicy()

    @Test fun sameStationRetriesAndAlternatesAreBounded() {
        assertTrue(policy.canRetrySame(1))
        assertTrue(policy.canRetrySame(2))
        assertFalse(policy.canRetrySame(3))
        assertTrue(policy.canTryAlternate(0))
        assertTrue(policy.canTryAlternate(2))
        assertFalse(policy.canTryAlternate(3))
        assertEquals(1_000L, policy.delayFor(1))
        assertEquals(2_000L, policy.delayFor(2))
        assertEquals(2_000L, policy.delayFor(3))
    }

    @Test fun transientAndServerFailuresRetryButClientErrorsDoNot() {
        assertTrue(policy.isRetryable(TuneFailure(TuneError.TIMEOUT)))
        assertTrue(policy.isRetryable(TuneFailure(TuneError.CONNECTION)))
        assertTrue(policy.isRetryable(TuneFailure(TuneError.TLS)))
        assertTrue(policy.isRetryable(TuneFailure(TuneError.SERVER)))
        assertTrue(policy.isRetryable(TuneFailure(TuneError.HTTP, 503)))
        assertFalse(policy.isRetryable(TuneFailure(TuneError.HTTP, 404)))
        assertFalse(policy.isRetryable(TuneFailure(TuneError.INVALID_RESPONSE)))
    }
}
