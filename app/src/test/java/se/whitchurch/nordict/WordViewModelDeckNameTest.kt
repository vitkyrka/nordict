package se.whitchurch.nordict

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The card-creating FAB names the target deck after the dictionary
 * ("Nordict - DLE") for a single word, but a combined multi-dictionary word
 * spans every selected dictionary, so its deck is named after the shared
 * language instead ("Nordict - ES").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WordViewModelDeckNameTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Ordboken.reset()
    }

    @After
    fun tearDown() {
        Ordboken.reset()
    }

    private fun wordViewModel(sources: String = ""): WordViewModel {
        val client = OkHttpClient()
        val dle = DleDictionary(client, "https://dle.rae.es")
        val est = EstDictionary(client, "https://www.rae.es/diccionario-estudiante")
        Ordboken.getInstance(app, client, arrayOf(dle, est))

        val uri = "https://dle.rae.es/frente".toHttpUrl()
        val handle = SavedStateHandle(mapOf("uri" to uri.toString()))
        if (sources.isNotEmpty()) handle["sources"] = sources
        return WordViewModel(app, handle).also {
            it.mWord = Word(
                dict = "DLE", mTitle = "frente", mSlug = "frente", summary = "",
                mText = "", uri = uri, baseUrl = "https://dle.rae.es",
                element = org.jsoup.Jsoup.parseBodyFragment("").body(), header = ""
            )
        }
    }

    @Test
    fun singleDictionaryWordUsesTheDictionaryTag() {
        val vm = wordViewModel()
        assertThat(vm.deckName).isEqualTo("Nordict - DLE")
    }

    @Test
    fun combinedWordUsesTheSharedLanguage() {
        val uri = "https://dle.rae.es/frente".toHttpUrl()
        val sources = MultiDict.sourcesToJson(
            listOf(CombSource("DLE", uri), CombSource("EST", uri))
        )
        val vm = wordViewModel(sources)
        assertThat(vm.deckName).isEqualTo("Nordict - ES")
    }
}