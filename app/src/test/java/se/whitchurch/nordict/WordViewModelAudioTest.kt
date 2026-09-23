package se.whitchurch.nordict

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the audio replay bug: the word screen's [ExoPlayer]
 * (androidx.media3) used to append every playback URL to its playlist, so after the first word
 * finished playing, tapping the play button again re-queued the same audio but
 * the player stayed at the ended item and never started it. [WordViewModel]
 * [WordViewModel.playAudio] now clears the playlist before re-queueing, so
 * each tap starts a fresh, single-item playlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WordViewModelAudioTest {

    @org.junit.Before
    fun setUp() {
        CollinsClearance.reset()
        InfopediaClearance.reset()
    }

    @org.junit.After
    fun tearDown() {
        CollinsClearance.reset()
        InfopediaClearance.reset()
    }

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

    @Test
    fun playAudioSendsSyncedClearanceToChallengedHostClips() {
        // The player's own HTTP stack never solved the Cloudflare challenge:
        // an Infopedia TTS clip must go out with the synced cf_clearance
        // cookie or the request 403s and playback fails with a source error.
        // The TTS endpoint is additionally hotlink-guarded, so the word page
        // goes along as Referer.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = WordViewModel(app, SavedStateHandle(mapOf("uri" to "https://example.com/frente")))
        vm.mWord = Word(
            "INFOPEDIA", "mesa", "mesa", "mesa",
            "https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa".toHttpUrl(),
            java.util.ArrayList(listOf("1"))
        )

        vm.playAudio(
            java.util.ArrayList(
                listOf("https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0")
            )
        )
        assertThat(vm.lastAudioHeaders["Referer"])
            .isEqualTo("https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa")

        InfopediaClearance.noteCookies(
            "https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa",
            "cf_clearance=tok456"
        )
        vm.playAudio(
            java.util.ArrayList(
                listOf("https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0")
            )
        )
        assertThat(vm.lastAudioHeaders["Cookie"]).isEqualTo("cf_clearance=tok456")
        assertThat(vm.lastAudioHeaders["Referer"])
            .isEqualTo("https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa")
        // Challenged-host clips go out with a browser UA (Cloudflare
        // bot-fights the player's library UA even with a valid clearance).
        assertThat(vm.lastAudioUserAgent).isEqualTo(WordViewModel.BROWSER_UA)

        // A later play without clearance held stops sending the cookie again.
        InfopediaClearance.reset()
        vm.playAudio(java.util.ArrayList(listOf("https://example.com/a.mp3")))
        assertThat(vm.lastAudioHeaders).isEmpty()
        assertThat(vm.lastAudioUserAgent)
            .isEqualTo(androidx.media3.common.MediaLibraryInfo.VERSION_SLASHY)
    }

    @Test
    fun playAudioUsesBrowserUaForCollinsClips() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = WordViewModel(app, SavedStateHandle(mapOf("uri" to "https://example.com/frente")))

        vm.playAudio(
            java.util.ArrayList(
                listOf("https://www.collinsdictionary.com/sounds/hwd_sounds/ES-ES-W0034030.mp3")
            )
        )
        assertThat(vm.lastAudioUserAgent).isEqualTo(WordViewModel.BROWSER_UA)
    }

    @Test
    fun replayUsesCachedFallbackFile() {
        // A clip recovered once replays straight from its cache file: the
        // playlist carries the file URI, so no 403 and no slow refetch.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = WordViewModel(app, SavedStateHandle(mapOf("uri" to "https://example.com/frente")))
        val clip = "https://www.collinsdictionary.com/sounds/hwd_sounds/ES-ES-W0034030.mp3"
        audioFallbackFile(app.cacheDir, clip).writeBytes(byteArrayOf(1, 2, 3))

        vm.playAudio(java.util.ArrayList(listOf(clip)))

        assertThat(vm.player.currentMediaItem?.localConfiguration?.uri?.scheme).isEqualTo("file")
    }

    @Test
    fun replayIgnoresEmptyCacheFiles() {
        // A zero-byte cache entry is not usable: the http URL plays instead,
        // so a corrupt file can still refetch through the fallback.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = WordViewModel(app, SavedStateHandle(mapOf("uri" to "https://example.com/frente")))
        val clip = "https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0"
        audioFallbackFile(app.cacheDir, clip).createNewFile()

        vm.playAudio(java.util.ArrayList(listOf(clip)))

        assertThat(vm.player.currentMediaItem?.localConfiguration?.uri.toString()).isEqualTo(clip)
    }
}