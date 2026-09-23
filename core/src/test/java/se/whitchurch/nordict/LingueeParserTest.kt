package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the Linguee Portuguese-English parser. Maps parsed
 * `Word`s through the exact same `WordJson.toWordData()` mapping the desktop
 * CLI emits, so the app, the CLI, and the test suite all agree on one JSON
 * schema.
 *
 * Linguee pages are served latin-1 (ISO-8859-15) with CRLF line endings, so
 * the fixtures are read with [Charsets.ISO_8859_1] (the default UTF-8 readText
 * would replace the accented bytes with U+FFFD).
 */
class LingueeParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseMesa() {
        val htmlFile = Goldens.fixture("../testdata/lingpt/mesa.html")
        val page = htmlFile.readText(Charsets.ISO_8859_1)
        val uri = httpUrl("https://www.linguee.pt/portugues-ingles/traducao/mesa.html")
        val words = LingueeParser.parse(page, uri, "LINGPT")

        // Two exact matches on the page: the headword "mesa" and the
        // spelling-variant "mês".
        assertThat(words).hasSize(2)

        val mesa = words[0]
        assertThat(mesa.mTitle).isEqualTo("mesa")
        assertThat(mesa.audio).hasSize(1)
        assertThat(mesa.audio[0]).isEqualTo(
            "https://www.linguee.pt/mp3/PT_PT/85/85770ae9def3473f559e0dbe0609060a-107.mp3"
        )

        // The two featured translations become two definitions, each with the
        // entry's Portuguese grammar/gender label and bilingual examples.
        assertThat(mesa.definitions).hasSize(2)
        assertThat(mesa.definitions[0].pos).isEqualTo("substantivo, feminino")
        assertThat(mesa.definitions[0].grammar).isEqualTo("substantivo, feminino")
        assertThat(mesa.definitions[0].gender).isEqualTo(Genders.FEMININE)
        assertThat(mesa.definitions[0].glosses).hasSize(1)
        assertThat(mesa.definitions[0].glosses[0].definition).isEqualTo("table")
        assertThat(mesa.definitions[0].glosses[0].examples).hasSize(2)
        assertThat(mesa.definitions[0].glosses[0].examples[0])
            .contains("A mesa estava cheia de comida durante o almoço.")
        assertThat(mesa.definitions[0].glosses[0].examples[0])
            .contains("The table was full of food during the lunch.")

        val mes = words[1]
        assertThat(mes.mTitle).isEqualTo("mês")
        assertThat(mes.definitions).isNotEmpty()
        assertThat(mes.definitions[0].gender).isEqualTo(Genders.MASCULINE)
        assertThat(mes.definitions[0].glosses[0].definition).isEqualTo("month")

        // Every entry on the page carries the full renderable entry set (both
        // exact matches), itself included.
        for (w in words) {
            assertThat(w.mHomonymEntries).hasSize(2)
            assertThat(w.mHomonymEntries.map { it.mTitle }).containsExactly("mesa", "mês").inOrder()
        }

        assertGolden(words, "../testdata/lingpt/mesa.json")
    }

    @Test
    fun testParseSearch() {
        val body = Goldens.fixtureText("../testdata/lingpt-search.json", Charsets.ISO_8859_1)

        val results = LingueeParser.parseSearch(body) { page ->
            httpUrl("https://www.linguee.pt$page")
        }

        assertThat(results).hasSize(10)
        assertThat(results[0].mTitle).isEqualTo("mesa")
        assertThat(results[0].uri.toString())
            .isEqualTo("https://www.linguee.pt/portugues-ingles/traducao/mesa.html")
        assertThat(results[1].mTitle).isEqualTo("mesa redonda")
        assertThat(results[1].uri.toString())
            .isEqualTo("https://www.linguee.pt/portugues-ingles/traducao/mesa+redonda.html")
    }

    @Test
    fun testParseSearchToleratesEmptyBodies() {
        val results = LingueeParser.parseSearch("") { page ->
            httpUrl("https://www.linguee.pt$page")
        }
        assertThat(results).isEmpty()
    }
}