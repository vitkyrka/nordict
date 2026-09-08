package se.whitchurch.nordict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SwitchFromDleTest {

    private lateinit var dleServer: MockWebServer
    private lateinit var colspanServer: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dleServer = MockWebServer()
        dleServer.start()
        colspanServer = MockWebServer()
        colspanServer.start()

        context.getSharedPreferences("ordboken", Context.MODE_PRIVATE).edit().clear().commit()
        Ordboken.reset()

        val client = OkHttpClient()
        Ordboken.getInstance(
            context,
            client,
            arrayOf(
                DleDictionary(client, dleServer.url("/").toString().removeSuffix("/")),
                CollinsSpanishEnglishDictionary(client, colspanServer.url("/").toString().removeSuffix("/"))
            )
        )
    }

    @After
    fun tearDown() {
        dleServer.shutdown()
        colspanServer.shutdown()
        Ordboken.reset()
    }

    private fun launchWord(path: String): ActivityScenario<WordActivity> {
        val intent = Intent(context, WordActivity::class.java)
            .setData(Uri.parse(dleServer.url(path).toString()))
        return ActivityScenario.launch(intent)
    }

    private fun await(timeoutMs: Long = 10000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private fun tapDictButton(activity: WordActivity, tag: String) {
        val group = activity.findViewById<RadioGroup>(R.id.dictRadio)
        val button = (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filterIsInstance<RadioButton>()
            .first { it.text.toString() == tag }
        button.performClick()
    }

    @Test
    fun switchingFromRaeCommaHeadwordSearchesTheBaseForm() {
        dleServer.enqueue(MockResponse().setBody(File("../testdata/dle/otro.html").readText()))
        // COLSPAN keys "otro"; the DLE display title "otro, tra" would produce
        // a bogus slug and no match.
        colspanServer.enqueue(MockResponse().setBody(
            """[{"title":"otro"}]"""
        ))

        launchWord("/otro").use { scenario ->
            var activity: WordActivity? = null
            scenario.onActivity { activity = it }

            await { Ordboken.getInstance(context).currentWord?.mTitle == "otro, tra" }

            scenario.onActivity { tapDictButton(it, "COLSPAN") }

            await {
                val started = shadowOf(activity!!).peekNextStartedActivity()
                started?.data?.toString() == colspanServer.url("/dictionary/spanish-english/otro").toString()
            }

            val searchRequest = colspanServer.takeRequest()
            assertThat(searchRequest.path).isEqualTo("/autocomplete/?q=otro&dictCode=spanish-english")
        }
    }
}