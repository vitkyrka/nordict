package se.whitchurch.nordict

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search sheet's history rows carry the same north-west fill arrow as
 * the current-word suggestion above them: tapping it fills the row's title
 * into the search field instead of opening the word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoryFillTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: android.app.Application
    private lateinit var ordboken: Ordboken
    private lateinit var fillLabel: String

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        NordictPrefs.clearBlocking(app)
        Ordboken.reset()
        val client = OkHttpClient()
        // History rows never hit the network; the dictionary only backs the
        // flag lookup (unknown tags fall back to flag_se anyway).
        ordboken = Ordboken.getInstance(app, client, arrayOf(SoDictionary(client)))
        fillLabel = app.getString(R.string.search_fill_row)

        kotlinx.coroutines.runBlocking {
            saveHistoryEntry(
                app,
                dict = "SO",
                title = "older",
                summary = "first lookup",
                url = "https://example.com/older",
                sources = ""
            )
            saveHistoryEntry(
                app,
                dict = "SO",
                title = "newer",
                summary = "second lookup",
                url = "https://example.com/newer",
                sources = ""
            )
        }
    }

    @After
    fun tearDown() {
        Ordboken.reset()
    }

    @Test
    fun historySuggestionRowsExposeFillButtons() {
        val filled = ArrayList<String>()
        var opened = 0
        composeRule.setContent {
            MaterialTheme {
                HistorySuggestionList(
                    context = app,
                    ordboken = ordboken,
                    onOpenWord = { _, _, _ -> opened++ },
                    onFillWord = { filled.add(it) }
                )
            }
        }

        // Newest first: both rows compose, each with its own fill arrow.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("newer").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("older").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(composeRule.onAllNodesWithContentDescription(fillLabel).fetchSemanticsNodes())
            .hasSize(2)

        // Filling the first row reports its title; the word is not opened.
        composeRule.onAllNodesWithContentDescription(fillLabel)[0].performClick()

        assertThat(filled).containsExactly("newer")
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun wordRowItemWithoutFillHasNoButton() {
        composeRule.setContent {
            MaterialTheme {
                WordRowItem(
                    row = WordRow(
                        id = 1L,
                        dict = "SO",
                        title = "older",
                        summary = "",
                        url = "https://example.com/older"
                    ),
                    ordboken = ordboken,
                    onOpen = {}
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("older").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(fillLabel).assertDoesNotExist()
    }
}
