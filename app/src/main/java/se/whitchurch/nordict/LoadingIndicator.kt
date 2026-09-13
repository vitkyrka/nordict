package se.whitchurch.nordict

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Robolectric's paused looper never finishes idling while a circular progress
 * indicator (an infinite Compose animation) is on screen, which hangs
 * `ActivityScenario.launch` / `ActivityController.setup` and the Robolectric
 * tests' looper-idling helpers. Under Robolectric render a static placeholder
 * so the tests terminate; production shows the real spinner.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    if (Build.FINGERPRINT == "robolectric") {
        Box(modifier = modifier.size(48.dp))
    } else {
        CircularProgressIndicator(modifier = modifier)
    }
}