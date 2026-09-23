package se.whitchurch.nordict

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive wavy loading indicator, shared by the word, search-results
 * and card screens.
 *
 * Robolectric's paused looper never finishes idling while an indeterminate
 * progress animation is on screen, which hangs `ActivityScenario.launch` /
 * `ActivityController.setup` and the Robolectric tests' looper-idling
 * helpers. Under Robolectric render a static placeholder (same test tag) so
 * the tests terminate; production shows the real wavy indicator.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    if (Build.FINGERPRINT == "robolectric") {
        Box(modifier = modifier.testTag("LoadingIndicator").size(48.dp))
    } else {
        CircularWavyProgressIndicator(modifier = modifier.testTag("LoadingIndicator"))
    }
}
