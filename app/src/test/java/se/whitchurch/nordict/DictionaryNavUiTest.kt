package se.whitchurch.nordict

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for [DictionaryNav]'s multi-dictionary row: the combining
 * language renders toggle chips that enable/disable dictionaries and a
 * long-press drag commits a reorder, all through the live [Ordboken] state
 * (which is what persists and reloads the word).
 *
 * FilterChip semantics are matched on the clickable chip node containing the
 * tag text (the label may or may not be merged into the chip node depending on
 * the material3 version), so assertions go against Ordboken/prefs state rather
 * than chip-internal `selected` semantics.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DictionaryNavUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: android.app.Application
    private val client = OkHttpClient()
    private lateinit var ordboken: Ordboken

    private fun dle() = DleDictionary(client)
    private fun est() = EstDictionary(client)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("ordboken", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        Ordboken.reset()
        ordboken = Ordboken.getInstance(
            app, client, arrayOf(dle(), est(), GdlcDictionary(client), SoDictionary(client))
        )
    }

    private fun setNav() {
        composeRule.setContent {
            MaterialTheme {
                DictionaryNav(ordboken = Ordboken.getInstance(app))
            }
        }
    }

    private fun setTopBar() {
        composeRule.setContent {
            MaterialTheme {
                LanguageTopBar(ordboken = Ordboken.getInstance(app))
            }
        }
    }

    /** The swap-to-last-language button labelled for [lang]. */
    private fun swapButton(lang: String) =
        composeRule.onNodeWithContentDescription("Byt till $lang")

    /** The clickable chip node whose text is [tag]. */
    private fun chip(tag: String) =
        composeRule.onNode(hasClickAction() and (hasText(tag) or hasAnyDescendant(hasText(tag))))

    private fun prefsDicts(lang: String): String =
        app.getSharedPreferences("ordboken", android.content.Context.MODE_PRIVATE)
            .getString("dicts_$lang", "")!!

    @Test
    fun combiningLanguageRendersChipsForAllCombiningDicts() {
        setNav()

        // Single-dict mode: both es combining dictionaries render as chips.
        composeRule.onNodeWithText("DLE").assertExists()
        composeRule.onNodeWithText("EST").assertExists()
        assertThat(ordboken.activeDicts).isEmpty()
        assertThat(ordboken.currentDictionary.tag).isEqualTo("DLE")
    }

    @Test
    fun togglingAChipOnEnablesCombinedMode() {
        setNav()

        chip("EST").performClick()
        composeRule.waitForIdle()

        assertThat(ordboken.activeDicts.map { it.tag }).containsExactly("DLE", "EST").inOrder()
        assertThat(ordboken.selectionSignature).isEqualTo("DLE,EST")
        assertThat(prefsDicts("es")).isEqualTo("DLE,EST")
    }

    @Test
    fun togglingAChipOffReturnsToSingleMode() {
        ordboken.setCurrentDictionaries(listOf("DLE", "EST"))
        setNav()

        chip("DLE").performClick()
        composeRule.waitForIdle()

        assertThat(ordboken.activeDicts).isEmpty()
        assertThat(ordboken.currentDictionary.tag).isEqualTo("EST")
        assertThat(prefsDicts("es")).isEqualTo("EST")
    }

    @Test
    fun togglingTheOnlyEnabledChipKeepsSingleSelection() {
        setNav()

        // Single DLE already selected; turning it "off" must not empty the
        // selection -- it falls back to the language's first combining dict.
        chip("DLE").performClick()
        composeRule.waitForIdle()

        assertThat(ordboken.activeDicts).isEmpty()
        assertThat(ordboken.currentDictionary.tag).isEqualTo("DLE")
        assertThat(prefsDicts("es")).isEqualTo("DLE")
    }

    @Test
    fun longPressDragReordersAndPersists() {
        ordboken.setCurrentDictionaries(listOf("DLE", "EST"))
        setNav()

        // Long-press EST and drag it left past DLE's midpoint.
        chip("EST").performTouchInput {
            down(center)
            advanceEventTime(1500)
            moveBy(Offset(-600f, 0f), delayMillis = 16)
            up()
        }
        composeRule.waitForIdle()

        // The drag's commit() hands the reordered selection to Ordboken.
        assertThat(ordboken.activeDicts.map { it.tag }).containsExactly("EST", "DLE").inOrder()
        assertThat(prefsDicts("es")).isEqualTo("EST,DLE")
    }

    @Test
    fun catalanLanguageRendersItsCombiningChips() {
        ordboken.setLanguage("ca")
        setNav()
        composeRule.onNodeWithText("GDLC").assertExists()
        composeRule.onAllNodes(hasClickAction() and hasText("GDLC"))
            .fetchSemanticsNodes().let { assertThat(it).hasSize(1) }
    }

    @Test
    fun spanishLanguageRendersItsCombiningChips() {
        ordboken.setLanguage("es")
        setNav()
        composeRule.onNodeWithText("DLE").assertExists()
        composeRule.onNodeWithText("EST").assertExists()
        composeRule.onAllNodes(hasClickAction() and hasText("GDLC"))
            .fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }

    @Test
    fun swapButtonShowsLastLangAndSwapsBackAndForth() {
        // Seeded with [es, ca, se]: fresh current language is es, so lastLang
        // is ca and the split button's swap segment reads "Byt till ca".
        setTopBar()
        swapButton("ca").assertExists().performClick()
        composeRule.waitForIdle()

        assertThat(ordboken.currentDictionary.lang).isEqualTo("ca")
        assertThat(ordboken.lastLang).isEqualTo("es")

        swapButton("es").assertExists().performClick()
        composeRule.waitForIdle()

        assertThat(ordboken.currentDictionary.lang).isEqualTo("es")
        assertThat(ordboken.lastLang).isEqualTo("ca")
    }

    @Test
    fun splitButtonDropdownOpensTheLanguageMenu() {
        setTopBar()

        // The trailing segment of the split button opens the language menu;
        // picking a language goes through Ordboken.setLanguage.
        composeRule.onNodeWithContentDescription("Byt språk").assertExists().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("se").performClick()
        composeRule.waitForIdle()

        assertThat(ordboken.currentDictionary.lang).isEqualTo("se")
        assertThat(ordboken.lastLang).isEqualTo("es")
    }

    @Test
    fun dictionaryChipsUpdateAfterLanguageSwap() {
        setNav()

        // Fresh: current language es renders DLE/EST.
        composeRule.onNodeWithText("DLE").assertExists()
        composeRule.onNodeWithText("EST").assertExists()

        // Swap es -> ca (the seeded lastLang): the chips must switch to the
        // Catalan set.
        swapButton("ca").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("GDLC").assertExists()
        composeRule.onNodeWithText("DLE").assertDoesNotExist()
        composeRule.onNodeWithText("EST").assertDoesNotExist()

        // Swap back ca -> es: the Spanish chips come back.
        swapButton("es").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("DLE").assertExists()
        composeRule.onNodeWithText("GDLC").assertDoesNotExist()
    }

    @Test
    fun dictionaryChipsUpdateAfterLanguageMenuChange() {
        setNav()

        // Pick se from the language menu: chips must switch to SO.
        composeRule.onNodeWithContentDescription("Byt språk").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("se").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SO").assertExists()
        composeRule.onNodeWithText("DLE").assertDoesNotExist()
        composeRule.onNodeWithText("GDLC").assertDoesNotExist()
    }

    @Test
    fun swapButtonHiddenWithoutASecondLanguage() {
        // The setUp seed persists "lastLang"=ca, so clear prefs before
        // reseeding a single-language Ordboken: it must have no swap target,
        // leaving just the language-menu button.
        Ordboken.reset()
        app.getSharedPreferences("ordboken", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        ordboken = Ordboken.getInstance(app, client, arrayOf(dle(), est()))
        setTopBar()
        composeRule.onNodeWithContentDescription("Byt till ca").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Byt till es").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Byt språk").assertExists()
    }
}