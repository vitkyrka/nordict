package se.whitchurch.nordict

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the audio replay bug: the word screen's [ExoPlayer]
 * used to append every playback URL to its playlist, so after the first word
 * finished playing, tapping the play button again re-queued the same audio but
 * the player stayed at the ended item and never started it. [WordViewModel]
 * [WordViewModel.playAudio] now clears the playlist before re-queueing, so
 * each tap starts a fresh, single-item playlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WordViewModelAudioTest {

    @Test
    fun playAudioResetsThePlaylistSoPlaybackCanRepeat() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = WordViewModel(app, SavedStateHandle(mapOf("uri" to "https://example.com/frente")))

        vm.playAudio(java.util.ArrayList(listOf("https://example.com/a.mp3")))
        vm.playAudio(java.util.ArrayList(listOf("https://example.com/b.mp3")))

        // The second play must replace the first URL instead of stacking it:
        // a stale ended-queue of [a, b] is what left the player never moving
        // past the already-played item on replay.
        assertThat(vm.player.mediaItemCount).isEqualTo(1)
        assertThat(vm.player.currentMediaItem?.localConfiguration?.uri.toString())
            .isEqualTo("https://example.com/b.mp3")
    }

    @Test
    fun playAudioWithMultipleUrlsQueuesThemTogether() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = WordViewModel(app, SavedStateHandle(mapOf("uri" to "https://example.com/frente")))

        vm.playAudio(
            java.util.ArrayList(
                listOf("https://example.com/a.mp3", "https://example.com/b.mp3")
            )
        )

        assertThat(vm.player.mediaItemCount).isEqualTo(2)
    }
}