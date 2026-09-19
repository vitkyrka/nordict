package se.whitchurch.nordict

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the DataStore-backed history (newest first, capped at
 * [HISTORY_MAX], no delete/clear API).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoryStoreTest {

    private val app: android.app.Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        NordictPrefs.clearBlocking(app)
    }

    private fun save(
        title: String,
        url: String = "https://example.com/$title",
        dict: String = "SO"
    ) = runBlocking {
        saveHistoryEntry(app, dict = dict, title = title, summary = "", url = url)
    }

    private fun load(limit: Int = HISTORY_MAX): List<WordRow> =
        runBlocking { loadHistoryRows(app, limit) }

    @Test
    fun newestEntryComesFirst() {
        save("first")
        save("second")

        val rows = load()
        assertThat(rows.map { it.title }).containsExactly("second", "first").inOrder()
    }

    @Test
    fun revisitingAnEntryMovesItToTheFrontWithoutDuplicating() {
        save("first")
        save("second")
        save("first")

        val rows = load()
        assertThat(rows.map { it.title }).containsExactly("first", "second").inOrder()
    }

    @Test
    fun historyIsCappedAtTenEntries() {
        for (i in 0 until HISTORY_MAX + 5) save("word$i")

        val rows = load()
        assertThat(rows).hasSize(HISTORY_MAX)
        assertThat(rows.first().title).isEqualTo("word${HISTORY_MAX + 4}")
        assertThat(rows.map { it.title }).doesNotContain("word0")
    }

    @Test
    fun limitIsRespected() {
        save("first")
        save("second")
        save("third")

        assertThat(load(limit = 2).map { it.title })
            .containsExactly("third", "second").inOrder()
    }

    @Test
    fun sourcesSurviveARoundTrip() {
        val sources = """[{"tag":"DLE","uri":"https://example.com/a"}]"""
        runBlocking {
            saveHistoryEntry(
                app, dict = "DLE", title = "frente", summary = "",
                url = "https://example.com/a", sources = sources
            )
        }

        val rows = load()
        assertThat(rows).hasSize(1)
        assertThat(rows.first().sources).isEqualTo(sources)
    }
}
