package se.whitchurch.nordict

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.BottomAppBarScrollBehavior
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The legacy (non-JSON) word path: the outer Compose `verticalScroll` owns the
 * page, and the word action bar collapses via the M3 exit-always scroll
 * behavior wired on the screen root's `nestedScroll` modifier. This pins the
 * wiring contract in isolation from a WebView: scrolling the word content down
 * drags the behavior's `heightOffset` below zero (bar collapsing) and
 * scrolling back up restores it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WordBarScrollUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scrollingTheContentDownCollapsesTheBarAndUpRestoresIt() {
        val scrollState = ScrollState(0)
        var behavior: BottomAppBarScrollBehavior? = null

        composeRule.setContent {
            MaterialTheme {
                val barBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior()
                behavior = barBehavior
                Box(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(barBehavior.nestedScrollConnection)
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        repeat(100) { i ->
                            Text("Item $i", modifier = Modifier.padding(16.dp))
                        }
                    }
                    BottomAppBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        scrollBehavior = barBehavior
                    ) {
                        Text("Bar")
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertThat(behavior!!.state.heightOffset).isEqualTo(0f)

        // Drag the word content up (scrolling the page down), as a real user
        // would: the exit-always behavior on the screen root's nested-scroll
        // chain must start collapsing the bar. Slow drags avoid a fling settle.
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = bottom - 150, endY = top + 50, durationMillis = 1500)
        }
        composeRule.waitForIdle()
        assertThat(behavior!!.state.heightOffset).isLessThan(0f)

        // Drag back down to the top: the bar must be fully restored.
        composeRule.onRoot().performTouchInput {
            swipeDown(startY = top + 50, endY = bottom - 150, durationMillis = 1500)
        }
        composeRule.onRoot().performTouchInput {
            swipeDown(startY = top + 50, endY = bottom - 150, durationMillis = 1500)
        }
        composeRule.waitForIdle()
        assertThat(behavior!!.state.heightOffset).isEqualTo(0f)
    }
}