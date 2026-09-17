package se.whitchurch.nordict

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.BottomAppBarScrollBehavior
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the floating word action bar ([FloatingWordToolbar]).
 *
 * The old M3 bottom app bar was swapped for a floating pill so the add-card
 * action could be a regular button and the overflow menu could move to the
 * leftmost position, mirroring the previous docked toolbar covered by
 * [NavigationRegressionTest]. The pronunciation button must be disabled when
 * the loaded word carries no audio (the play button used to stay enabled and
 * silently do nothing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingWordToolbarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var behavior: BottomAppBarScrollBehavior
    private var played = false
    private var cardRequested = false

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<android.app.Application>().getString(resId)

    private fun setBar(audioEnabled: Boolean) {
        played = false
        cardRequested = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    FloatingWordToolbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter),
                        scrollBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior()
                            .also { behavior = it },
                        audioEnabled = audioEnabled,
                        autoPlay = false,
                        onPlayAudio = { played = true },
                        onOpenInBrowser = {},
                        onToggleAutoPlay = {},
                        onResetZoom = {},
                        onAddCard = { cardRequested = true }
                    )
                }
            }
        }
    }

    @Test
    fun playButtonDisabledWhenTheWordHasNoAudio() {
        setBar(audioEnabled = false)
        composeRule
            .onNodeWithContentDescription(string(R.string.menu_play_audio))
            .assertIsNotEnabled()
    }

    @Test
    fun playButtonEnabledWhenTheWordHasAudio() {
        setBar(audioEnabled = true)
        composeRule
            .onNodeWithContentDescription(string(R.string.menu_play_audio))
            .assertIsEnabled()
    }

    @Test
    fun playButtonInvokesItsCallback() {
        setBar(audioEnabled = true)
        composeRule.onNodeWithContentDescription(string(R.string.menu_play_audio)).performClick()
        assertThat(played).isTrue()
    }

    @Test
    fun buttonOrderIsPlayThenAddCardThenMenuRightmost() {
        setBar(audioEnabled = true)
        val play = composeRule.onNodeWithContentDescription(string(R.string.menu_play_audio)).getBoundsInRoot()
        val addCard = composeRule.onNodeWithContentDescription(string(R.string.menu_add_card)).getBoundsInRoot()
        val menu = composeRule.onNodeWithContentDescription(string(R.string.menu_more)).getBoundsInRoot()
        assertThat(play.left.value).isLessThan(addCard.left.value)
        assertThat(addCard.left.value).isLessThan(menu.left.value)
    }

    @Test
    fun addCardIsAnOrdinaryButtonThatInvokesItsCallback() {
        setBar(audioEnabled = false)
        composeRule.onNodeWithContentDescription(string(R.string.menu_add_card)).performClick()
        assertThat(cardRequested).isTrue()
    }

@Test
    fun scrollingDownPushesTheBarOffScreenAndBackUpRestoresIt() {
        setBar(audioEnabled = true)
        // Measure the play button, a descendant whose boundsInRoot reflects
        // the Surface's ancestor offset — the testTag node itself is the
        // outer wrapper and ignores its own inner offset.
        val visible = composeRule
            .onNodeWithContentDescription(string(R.string.menu_play_audio))
            .getBoundsInRoot()
        // The pill measured itself and published its travel distance (M3's
        // BottomAppBarLayout does the same for the real bottom bar); without it
        // heightOffset could never leave 0.
        assertThat(behavior.state.heightOffsetLimit).isLessThan(0f)

        // A downward WebView scroll drives the clamped heightOffset to that
        // limit; the bar must translate down by its own height + the bottom
        // padding, so the play button's top is pushed at least to where its
        // bottom used to be.
        composeRule.runOnIdle {
            behavior.state.heightOffset = behavior.state.heightOffsetLimit
        }
        composeRule.waitForIdle()
        val hidden = composeRule
            .onNodeWithContentDescription(string(R.string.menu_play_audio))
            .getBoundsInRoot()
        assertThat(hidden.top.value).isAtLeast(visible.bottom.value)

        // A scroll back up returns it to its resting spot.
        composeRule.runOnIdle { behavior.state.heightOffset = 0f }
        composeRule.waitForIdle()
        val restored = composeRule
            .onNodeWithContentDescription(string(R.string.menu_play_audio))
            .getBoundsInRoot()
        assertThat(restored.top.value).isEqualTo(visible.top.value)
    }

    @Test
    fun autoplayItemIsReachableFromTheMenu() {
        setBar(audioEnabled = true)
        composeRule.onNodeWithContentDescription(string(R.string.menu_more)).performClick()
        composeRule.onNodeWithText(string(R.string.menu_autoplay)).assertIsDisplayed()
    }
}