package se.whitchurch.nordict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.widget.SearchView
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
class SwitchDictionaryTest {

    private lateinit var estServer: MockWebServer
    private lateinit var colspanServer: MockWebServer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        estServer = MockWebServer()
        estServer.start()
        colspanServer = MockWebServer()
        colspanServer.start()

        context.getSharedPreferences("ordboken", Context.MODE_PRIVATE).edit().clear().commit()
        Ordboken.reset()

        val client = OkHttpClient()
        Ordboken.getInstance(
            context,
            client,
            arrayOf(
                EstDictionary(client, estServer.url("/").toString().removeSuffix("/")),
                CollinsSpanishEnglishDictionary(client, colspanServer.url("/").toString().removeSuffix("/"))
            )
        )
    }

    @After
    fun tearDown() {
        estServer.shutdown()
        colspanServer.shutdown()
        Ordboken.reset()
    }

    private fun launchEstWord(path: String): ActivityScenario<WordActivity> {
        val intent = Intent(context, WordActivity::class.java)
            .setData(Uri.parse(estServer.url(path).toString()))
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
    fun switchingDictNavigatesToUniqueExactMatch() {
        estServer.enqueue(MockResponse().setBody(File("../testdata/est/cagar.html").readText()))
        colspanServer.enqueue(MockResponse().setBody(
            """[{"title":"cagar"},{"title":"cagarse"}]"""
        ))

        launchEstWord("/cagar").use { scenario ->
            var activity: WordActivity? = null
            scenario.onActivity { activity = it }

            await { Ordboken.getInstance(context).currentWord?.mTitle == "cagar" }

            scenario.onActivity { tapDictButton(it, "COLSPAN") }

            await {
                val intent = shadowOf(activity!!).peekNextStartedActivity()
                intent?.data?.toString() == colspanServer.url("/dictionary/spanish-english/cagar").toString()
            }

            val intent = shadowOf(activity!!).peekNextStartedActivity()
            assertThat(intent).isNotNull()
            assertThat(intent!!.data.toString())
                .isEqualTo(colspanServer.url("/dictionary/spanish-english/cagar").toString())
        }
    }

    @Test
    fun switchingDictWithoutExactMatchFillsSearchBar() {
        estServer.enqueue(MockResponse().setBody(File("../testdata/est.html").readText()))
        // Homographs share the exact title, so there is no unique match.
        colspanServer.enqueue(MockResponse().setBody(
            """[{"title":"frente"},{"title":"frente"}]"""
        ))

        launchEstWord("/frente").use { scenario ->
            var activity: WordActivity? = null
            scenario.onActivity { activity = it }

            await { Ordboken.getInstance(context).currentWord?.mTitle == "frente" }

            // Ensure the search view exists (on a device the options menu is
            // always created; Robolectric needs it forced).
            scenario.onActivity { act ->
                val menu = androidx.appcompat.view.menu.MenuBuilder(act)
                act.menuInflater.inflate(R.menu.main, menu)
                act.onCreateOptionsMenu(menu)
            }

            scenario.onActivity { tapDictButton(it, "COLSPAN") }

            await {
                val search = activity!!.findViewById<SearchView>(R.id.mySearchView)
                search?.query?.toString() == "frente"
            }

            assertThat(shadowOf(activity!!).nextStartedActivity).isNull()
        }
    }
}