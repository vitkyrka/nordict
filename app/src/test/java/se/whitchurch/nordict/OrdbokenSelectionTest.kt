package se.whitchurch.nordict

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * State/persistence tests for the multi-dictionary selection on top of
 * [Ordboken]: the per-language enabled+ordered list survives across
 * re-instantiation, `setLanguage` restores each language's own selection, and
 * invalid / non-combining stored tags fall back to single-dict mode.
 *
 * Seeded with DLE/EST (es), DIDAC/GDLC (ca) — all combining — and SO (se), a
 * legacy single-dict language. No MockWebServer needed: selection state never
 * touches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OrdbokenSelectionTest {

    private lateinit var app: android.app.Application
    private val client = OkHttpClient()

    private fun dicts() = arrayOf(
        DleDictionary(client),
        EstDictionary(client),
        DidacDictionary(client),
        GdlcDictionary(client),
        SoDictionary(client)
    )

    private fun ordboken(tags: Array<se.whitchurch.nordict.Dictionary> = dicts()): Ordboken =
        Ordboken.getInstance(app, client, tags)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("ordboken", Context.MODE_PRIVATE).edit().clear().commit()
        Ordboken.reset()
    }

    private fun restart(): Ordboken {
        Ordboken.reset()
        return ordboken()
    }

    private fun prefsKey(lang: String): String =
        app.getSharedPreferences("ordboken", Context.MODE_PRIVATE)
            .getString("dicts_$lang", "")!!

    @Test
    fun toggleAppendsAndPersistsMultiSelection() {
        val ord = ordboken()
        assertThat(ord.currentDictionary.tag).isEqualTo("DLE")
        assertThat(ord.activeDicts).isEmpty()

        // Toggling EST on from single-DLE appends it (order kept).
        assertThat(ord.toggleDictionary("EST")).isTrue()
        assertThat(ord.activeDicts.map { it.tag }).containsExactly("DLE", "EST").inOrder()
        assertThat(prefsKey("es")).isEqualTo("DLE,EST")

        // Toggling OFF collapses to single-dict mode: only one dict remains.
        assertThat(ord.toggleDictionary("DLE")).isTrue()
        assertThat(ord.activeDicts).isEmpty()
        assertThat(ord.currentDictionary.tag).isEqualTo("EST")

        // A restart restores what was persisted (as single-dict mode).
        val restored = restart()
        assertThat(restored.activeDicts).isEmpty()
        assertThat(restored.currentDictionary.tag).isEqualTo("EST")
    }

    @Test
    fun singleDictSelectionPersistsSingleTagList() {
        val ord = ordboken()
        ord.setCurrentDictionary("est")

        assertThat(ord.activeDicts).isEmpty()
        assertThat(ord.currentDictionary.tag).isEqualTo("EST")
        assertThat(prefsKey("es")).isEqualTo("EST")

        val restored = restart()
        assertThat(restored.activeDicts).isEmpty()
        assertThat(restored.currentDictionary.tag).isEqualTo("EST")
    }

    @Test
    fun reorderPersistsOrder() {
        val ord = ordboken()
        ord.setCurrentDictionaries(listOf("DLE", "EST"))
        assertThat(ord.setDictionaryOrder(listOf("EST", "DLE"))).isTrue()

        assertThat(ord.activeDicts.map { it.tag }).containsExactly("EST", "DLE").inOrder()
        assertThat(prefsKey("es")).isEqualTo("EST,DLE")

        val restored = restart()
        assertThat(restored.activeDicts.map { it.tag }).containsExactly("EST", "DLE").inOrder()
    }

    @Test
    fun setDictionaryOrderRejectsNonPermutationsAndSingleMode() {
        val ord = ordboken()
        // Not in multi mode: nothing to reorder.
        assertThat(ord.setDictionaryOrder(listOf("EST", "DLE"))).isFalse()

        ord.setCurrentDictionaries(listOf("DLE", "EST"))
        // A different member set is rejected (DIDAC is Catalan).
        assertThat(ord.setDictionaryOrder(listOf("DLE", "DIDAC"))).isFalse()
        // A size mismatch is rejected.
        assertThat(ord.setDictionaryOrder(listOf("DLE", "EST", "EST"))).isFalse()
        // The selection is unchanged after a rejected call.
        assertThat(ord.activeDicts.map { it.tag }).containsExactly("DLE", "EST").inOrder()
    }

    @Test
    fun setLanguageRestoresEachLanguagesOwnSelection() {
        val ord = ordboken()

        // Spanish: DLE + EST combined.
        ord.setCurrentDictionaries(listOf("DLE", "EST"))
        // Catalan: DIDAC + GDLC combined.
        ord.setLanguage("ca")
        assertThat(ord.currentDictionary.tag).isEqualTo("DIDAC")
        ord.setCurrentDictionaries(listOf("DIDAC", "GDLC"))

        ord.setLanguage("es")
        assertThat(ord.activeDicts.map { it.tag }).containsExactly("DLE", "EST").inOrder()
        assertThat(ord.currentDictionary.tag).isEqualTo("DLE")
        assertThat(ord.selectionSignature).isEqualTo("DLE,EST")

        ord.setLanguage("ca")
        assertThat(ord.activeDicts.map { it.tag }).containsExactly("DIDAC", "GDLC").inOrder()
        assertThat(ord.selectionSignature).isEqualTo("DIDAC,GDLC")

        // A language with only a legacy dict stays single-dict.
        ord.setLanguage("se")
        assertThat(ord.activeDicts).isEmpty()
        assertThat(ord.currentDictionary.tag).isEqualTo("SO")
    }

    @Test
    fun restoreDropsInvalidOrNonCombineableTags() {
        // Seeded prefs with a non-combining tag in the multi selection.
        app.getSharedPreferences("ordboken", Context.MODE_PRIVATE)
            .edit().putString("dicts_es", "DLE,SO").commit()
        val invalid = ordboken()
        assertThat(invalid.activeDicts).isEmpty()
        assertThat(invalid.currentDictionary.tag).isEqualTo("DLE")

        // Unknown tags are dropped too.
        Ordboken.reset()
        app.getSharedPreferences("ordboken", Context.MODE_PRIVATE)
            .edit().putString("dicts_es", "DLE,NOPE").commit()
        val unknown = ordboken()
        assertThat(unknown.activeDicts).isEmpty()
        assertThat(unknown.currentDictionary.tag).isEqualTo("DLE")

        // A cross-language mix keeps only the current language's members.
        Ordboken.reset()
        app.getSharedPreferences("ordboken", Context.MODE_PRIVATE)
            .edit().putString("dicts_es", "DLE,GDLC").commit()
        val mixed = ordboken()
        assertThat(mixed.activeDicts).isEmpty()
        assertThat(mixed.currentDictionary.tag).isEqualTo("DLE")
    }

    @Test
    fun toggleOffTheLastDictStaysOnTheFirstCombiningDict() {
        val ord = ordboken()
        // The only enabled dict cannot be turned off: the selection falls back
        // to the language's first combining dictionary.
        assertThat(ord.toggleDictionary("DLE")).isTrue()
        assertThat(ord.activeDicts).isEmpty()
        assertThat(ord.currentDictionary.tag).isEqualTo("DLE")
        assertThat(prefsKey("es")).isEqualTo("DLE")
    }

    @Test
    fun toggleDictionaryRejectsNonCombiningAndUnknownTags() {
        val ord = ordboken()
        assertThat(ord.toggleDictionary("SO")).isFalse() // legacy, non-combining
        assertThat(ord.toggleDictionary("NOPE")).isFalse()
        assertThat(ord.activeDicts).isEmpty()
        assertThat(ord.currentDictionary.tag).isEqualTo("DLE")
    }

    @Test
    fun combiningHelpers() {
        val ord = ordboken()
        assertThat(ord.combiningDictsForLang("es").map { it.tag })
            .containsExactly("DLE", "EST").inOrder()
        assertThat(ord.combiningDictsForLang("ca").map { it.tag })
            .containsExactly("DIDAC", "GDLC").inOrder()
        assertThat(ord.combiningDictsForLang("se")).isEmpty()
        assertThat(ord.hasCombiningForLang("es")).isTrue()
        assertThat(ord.hasCombiningForLang("ca")).isTrue()
        assertThat(ord.hasCombiningForLang("se")).isFalse()
    }

    @Test
    fun freshInstallSeedsLastLangToTheFirstOtherLanguage() {
        val ord = ordboken()
        // Languages are [es, ca, se] and the fresh current language is es.
        assertThat(ord.lastLang).isEqualTo("ca")
    }

    @Test
    fun setLanguageRecordsTheLanguageLeftAsLastLang() {
        val ord = ordboken()
        assertThat(ord.lastLang).isEqualTo("ca")

        ord.setLanguage("se")
        assertThat(ord.currentDictionary.tag).isEqualTo("SO")
        assertThat(ord.lastLang).isEqualTo("es")

        ord.setLanguage("ca")
        assertThat(ord.currentDictionary.tag).isEqualTo("DIDAC")
        assertThat(ord.lastLang).isEqualTo("se")

        // A no-op switch to the current language does not clobber lastLang.
        ord.setLanguage("ca")
        assertThat(ord.lastLang).isEqualTo("se")
    }

    @Test
    fun swapLangTogglesBetweenTwoLanguages() {
        val ord = ordboken()
        assertThat(ord.swapLang()).isTrue()
        assertThat(ord.currentDictionary.lang).isEqualTo("ca")
        assertThat(ord.currentDictionary.tag).isEqualTo("DIDAC")
        assertThat(ord.lastLang).isEqualTo("es")

        assertThat(ord.swapLang()).isTrue()
        assertThat(ord.currentDictionary.lang).isEqualTo("es")
        assertThat(ord.currentDictionary.tag).isEqualTo("DLE")
        assertThat(ord.lastLang).isEqualTo("ca")
    }

    @Test
    fun lastLangSurvivesRestart() {
        val ord = ordboken()
        ord.swapLang() // es -> ca; lastLang becomes es
        ord.prefsEditor.commit() // the real app persists the state on pause

        val restored = restart()
        assertThat(restored.currentDictionary.lang).isEqualTo("ca")
        assertThat(restored.currentDictionary.tag).isEqualTo("DIDAC")
        assertThat(restored.lastLang).isEqualTo("es")

        // The restored pair keeps toggling: the next swap goes back to es.
        assertThat(restored.swapLang()).isTrue()
        assertThat(restored.currentDictionary.lang).isEqualTo("es")
        assertThat(restored.currentDictionary.tag).isEqualTo("DLE")
    }

    @Test
    fun dictSwitchesDoNotClobberLastLang() {
        val ord = ordboken()
        assertThat(ord.lastLang).isEqualTo("ca")

        ord.setCurrentDictionary("est")
        assertThat(ord.lastLang).isEqualTo("ca")
        ord.toggleDictionary("EST")
        assertThat(ord.lastLang).isEqualTo("ca")
    }

    @Test
    fun swapLangRequiresASecondLanguage() {
        // Only one language registered: nothing to swap to and no seed.
        val ord = ordboken(arrayOf(DleDictionary(client), EstDictionary(client)))
        assertThat(ord.lastLang).isNull()
        assertThat(ord.swapLang()).isFalse()
    }
}