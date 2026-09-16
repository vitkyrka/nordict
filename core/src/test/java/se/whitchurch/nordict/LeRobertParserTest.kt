package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the LeRobert parser. Maps parsed `Word`s through the
 * exact same `WordJson.toWordData()` mapping the desktop CLI emits.
 */
class LeRobertParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseTable() {
        val htmlFile = File("../testdata/rob/table.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://dictionnaire.lerobert.com/definition/table")
        val words = LeRobertParser.parse(page, uri, "ROB")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("table")
        assertThat(word.audio).isNotEmpty()
        assertThat(word.audio[0]).contains("mp3")

        // Definitions: multiple senses from the d_ptma tree.
        assertThat(word.definitions.size).isAtLeast(10)
        assertThat(word.definitions[0].pos).isEqualTo("nom féminin")
        assertThat(word.definitions[0].grammar).isEqualTo("nom féminin")
        assertThat(word.definitions[0].gender).isEqualTo(Genders.FEMININE)

        // First definition should have the umbrella sense.
        assertThat(word.definitions[0].definition).contains("Meuble sur pied")

        // Domain marker "(Surface plane)" should appear on one definition.
        assertThat(word.definitions.any { it.domain == "Surface plane" }).isTrue()

        // Idioms: "locution"-marked expressions.
        assertThat(word.idioms).isNotEmpty()
        val tableRonde = word.idioms.find { it.idiom.contains("Se mettre à table") }
        assertThat(tableRonde).isNotNull()

        assertGolden(words, "../testdata/rob/table.json")
    }

    @Test
    fun testParseSearch() {
        val body = File("../testdata/rob-search.json").readText()

        val results = LeRobertParser.parseSearch(body) { page ->
            httpUrl("https://dictionnaire.lerobert.com$page")
        }

        // Only "def" entries survive; the conjugation and (duplicate) synonyms
        // views are dropped.
        assertThat(results).hasSize(4)
        assertThat(results[0].mTitle).isEqualTo("table")
        assertThat(results[0].uri.toString())
            .isEqualTo("https://dictionnaire.lerobert.com/definition/table")
        assertThat(results[1].mTitle).isEqualTo("tableau")
        assertThat(results.any { it.uri.toString().contains("/synonymes/") }).isFalse()
        assertThat(results.any { it.uri.toString().contains("/conjugaison/") }).isFalse()

        // The synonyms view repeated the "table" display, which made the exact
        // match ambiguous; only the definition entry remains.
        val exact = ExactMatch.resolve("table", results)
        assertThat(exact).isNotNull()
        assertThat(exact!!.uri.toString())
            .isEqualTo("https://dictionnaire.lerobert.com/definition/table")
    }

    @Test
    fun testParseSearchToleratesNonArrayBodies() {
        val results = LeRobertParser.parseSearch("{}") { page ->
            httpUrl("https://dictionnaire.lerobert.com$page")
        }

        assertThat(results).isEmpty()
    }
}
