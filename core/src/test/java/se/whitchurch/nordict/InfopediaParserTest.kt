package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the Infopedia parser. Maps parsed `Word`s through
 * the exact same `WordJson.toWordData()` mapping the desktop CLI emits, so the
 * app, the CLI, and the test suite all agree on one JSON schema.
 */
class InfopediaParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseMesa() {
        val htmlFile = Goldens.fixture("../testdata/infopedia/mesa.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa")
        val words = InfopediaParser.parse(page, uri, "INFOPEDIA")

        // A single headword article ("mesa"); no homographs on this page.
        assertThat(words).hasSize(1)

        val mesa = words[0]
        assertThat(mesa.mTitle).isEqualTo("mesa")
        assertThat(mesa.xrefs).containsExactly("1")

        // Orthoepy + syllabification + phonetic transcription make up the
        // pronunciation line; etymology is the `.dolVverbeteEtim` text with the
        // "Etimologia:" label stripped.
        assertThat(mesa.pronunciation).isEqualTo("me.sa ˈmezɐ /ê/")
        assertThat(mesa.etymology).startsWith("Do latim mensa-")

        // Word TTS clip.
        assertThat(mesa.audio).containsExactly(
            "https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0"
        )

        // 12 senses under the single "nome feminino" POS group.
        assertThat(mesa.definitions).hasSize(12)
        assertThat(mesa.definitions[0].pos).isEqualTo("nome feminino")
        assertThat(mesa.definitions[0].grammar).isEqualTo("nome feminino")
        assertThat(mesa.definitions[0].gender).isEqualTo(Genders.FEMININE)
        assertThat(mesa.definitions[0].senseNumber).isEqualTo("1")
        assertThat(mesa.definitions[0].domain).isEmpty()
        assertThat(mesa.definitions[0].glosses).hasSize(1)
        assertThat(mesa.definitions[0].glosses[0].definition).contains("móvel")

        // Secondary senses: domain/register/geo markers re-labelled per sense.
        assertThat(mesa.definitions[2].senseNumber).isEqualTo("3")
        assertThat(mesa.definitions[2].domain).isEqualTo("GEOGRAFIA")
        assertThat(mesa.definitions[10].senseNumber).isEqualTo("11")
        assertThat(mesa.definitions[10].register).isEqualTo("figurado")
        // Alternative translations of one sense become a comma-joined gloss.
        assertThat(mesa.definitions[10].glosses[0].definition)
            .isEqualTo("alimentação, comida, passadio")
        assertThat(mesa.definitions[11].senseNumber).isEqualTo("12")
        assertThat(mesa.definitions[11].geo).isEqualTo("Brasil")
        assertThat(mesa.definitions[11].register).isEqualTo("gíria")

        // Synonyms from the word-level `relacoesSinonimosContainer` box attach
        // to the first definition, with absolute links resolved against the base.
        assertThat(mesa.definitions[0].synonyms).isNotEmpty()
        assertThat(mesa.definitions[0].synonyms[0].href).startsWith("https://www.infopedia.pt/")

        // 8 locuções, their senses in `.dolRow` glosses.
        assertThat(mesa.idioms).hasSize(8)
        assertThat(mesa.idioms.map { it.idiom[0] }).isNotEmpty()
        assertThat(mesa.idioms[0].glosses).isNotEmpty()
        assertThat(mesa.idioms[0].glosses[0].definition).isNotEmpty()

        // Single-entry page: no homonym nav.
        assertThat(mesa.mHomonymEntries).isEmpty()

        assertGolden(words, "../testdata/infopedia/mesa.json")
    }

    @Test
    fun testParseSearch() {
        val body = Goldens.fixtureText("../testdata/infopedia-search.json")
        // The sugestao-pesquisa response is JSON wrapping an HTML fragment;
        // the dictionary unwraps `html` before handing it to parseSearch, so
        // the test mirrors that path.
        val html = com.google.gson.JsonParser.parseString(body).asJsonObject.get("html")?.asString ?: ""

        val results = InfopediaParser.parseSearch(html) { title ->
            httpUrl("https://www.infopedia.pt/dicionarios/lingua-portuguesa/$title")
        }

        assertThat(results).hasSize(5)
        assertThat(results[0].mTitle).isEqualTo("mesa")
        assertThat(results[0].uri.toString())
            .isEqualTo("https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa")
        assertThat(results[3].mTitle).isEqualTo("mesada")
        assertThat(results[3].uri.toString())
            .isEqualTo("https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesada")
    }

    @Test
    fun testParseSearchToleratesEmptyBodies() {
        val results = InfopediaParser.parseSearch("") { title ->
            httpUrl("https://www.infopedia.pt/dicionarios/lingua-portuguesa/$title")
        }
        assertThat(results).isEmpty()
    }
}