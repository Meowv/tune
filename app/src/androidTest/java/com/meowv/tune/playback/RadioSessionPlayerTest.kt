package com.meowv.tune.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class RadioSessionPlayerTest {
    @Test fun listenersReadSessionCommandsAndSingleItemTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var exoPlayer: ExoPlayer
        lateinit var sessionPlayer: RadioSessionPlayer
        val transitions = mutableListOf<String?>()
        val snapshots = mutableListOf<Pair<Player, String?>>()
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions.add(mediaItem?.mediaId)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (!events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) return
                assertTrue(events.contains(Player.EVENT_TIMELINE_CHANGED))
                assertTrue(events.contains(Player.EVENT_MEDIA_METADATA_CHANGED))
                assertEquals(1, player.currentTimeline.windowCount)
                assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                snapshots.add(player to player.mediaMetadata.title?.toString())
            }
        }
        instrumentation.runOnMainSync {
            exoPlayer = ExoPlayer.Builder(instrumentation.targetContext).build()
            sessionPlayer = RadioSessionPlayer(exoPlayer, object : RadioSessionPlayer.SessionCommands {
                override val canPrevious = true
                override val canNext = true
                override fun onSessionPlay() = Unit
                override fun onSessionPause() = Unit
                override fun onSessionNext() = Unit
                override fun onSessionPrevious() = Unit
                override fun onSessionStop() = Unit
            })
        }
        try {
            instrumentation.runOnMainSync {
                assertFalse(exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                assertTrue(sessionPlayer.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
                assertFalse(sessionPlayer.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                sessionPlayer.addListener(listener)
                sessionPlayer.addListener(listener)
                exoPlayer.setMediaItem(item("A"))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { exoPlayer.setMediaItem(item("B")) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                sessionPlayer.removeListener(listener)
                exoPlayer.setMediaItem(item("C"))
            }
            instrumentation.waitForIdleSync()
            assertEquals(listOf("A", "B"), transitions)
            assertEquals(listOf("A", "B"), snapshots.map { it.second })
            snapshots.forEach { assertSame(sessionPlayer, it.first) }
        } finally {
            instrumentation.runOnMainSync { sessionPlayer.release() }
        }
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://test.example/$id.mp3")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(id).build()).build()
}
