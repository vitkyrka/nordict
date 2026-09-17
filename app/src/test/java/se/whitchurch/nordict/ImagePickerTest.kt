package se.whitchurch.nordict

import android.os.Looper
import android.webkit.WebView
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ImagePicker renders the actual picker UI by loading the JS-built HTML at
 * [GSTATIC_SERVER] via `loadDataWithBaseURL`, not by keeping the raw Google
 * Images results page on screen.
 *
 * The Compose migration regressed this when `pushPickerHtml` was turned into
 * a no-op, so the picker was never displayed and `blockNetworkImage` stayed
 * `true` forever. `toDataURL()` needs the thumbnails (host-relative
 * `/images?q=tbn:...`, resolved against the gstatic base) to be same-origin,
 * otherwise the canvas is tainted and selecting images throws SecurityError.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImagePickerTest {

    private lateinit var scenario: ActivityScenario<ImagePicker>

    @After
    fun tearDown() {
        Ordboken.reset()
        if (::scenario.isInitialized) scenario.close()
        ImagePickerWebViewHolder.current = null
    }

    private fun pumpMainLooper() {
        runCatching { Snapshot.sendApplyNotifications() }
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitWebView(): WebView {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val start = System.currentTimeMillis()
        while (ImagePickerWebViewHolder.current == null && System.currentTimeMillis() - start < 15_000) {
            pumpMainLooper()
            Thread.sleep(20)
        }
        return ImagePickerWebViewHolder.current ?: run {
            val fallback = WebView(app)
            ImagePickerWebViewHolder.current = fallback
            fallback
        }
    }

    @Test
    fun pushPickerHtmlRendersThePickerAtTheGstaticBaseWithThePickScript() {
        scenario = ActivityScenario.launch(ImagePicker::class.java)
        val webView = awaitWebView()

        scenario.onActivity { activity ->
            activity.WcmJsObject().pushPickerHtml("<html><body><img src=\"/images?q=tbn:x\"></body></html>")
        }
        pumpMainLooper()

        val load = shadowOf(webView).lastLoadDataWithBaseURL
        assertThat(load).isNotNull()
        assertThat(load.baseUrl).isEqualTo(GSTATIC_SERVER)
        // The picker document re-includes imagepicker.js so toggle/update work.
        assertThat(load.data).contains("<script>")
        assertThat(load.data).contains("getPickerHtml")
        assertThat(load.data).contains("toDataURL")
        assertThat(load.data).contains("src=\"/images?q=tbn:x\"")
        assertThat(load.mimeType).isEqualTo("text/html")
        assertThat(load.encoding).isEqualTo("UTF-8")
    }

    @Test
    fun pageFinishedOnGstaticUnblocksNetworkImages() {
        scenario = ActivityScenario.launch(ImagePicker::class.java)
        val webView = awaitWebView()
        assertThat(webView.settings.blockNetworkImage).isTrue()

        scenario.onActivity {
            webView.webViewClient.onPageFinished(webView, GSTATIC_SERVER)
        }
        pumpMainLooper()

        assertThat(webView.settings.blockNetworkImage).isFalse()
    }
}