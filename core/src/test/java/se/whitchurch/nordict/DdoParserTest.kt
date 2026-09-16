package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the DDO/SDO parser. Maps parsed `Word`s through the
 * exact same `WordJson.toWordData()` mapping the desktop CLI emits.
 */
class DdoParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseArbejde() {
        val htmlFile = File("../testdata/ddo/arbejde.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://ordnet.dk/ddo/ordbog?entry_id=11002240&query=arbejde")
        val words = DdoParser.parse(page, uri, "DDO")

        // A single article (homographs live on separate entry pages).
        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("arbejde")
        assertThat(word.pos).isEqualTo(Pos.NOUN)

        // Header meta: pronunciation, "bøjning" (hyphens stand for the
        // headword), etymology, and the speaker.gif TTS clip.
        assertThat(word.pronunciation).contains("ɑ")
        assertThat(word.conjugation).isEqualTo("arbejdet, arbejder, arbejderne")
        assertThat(word.etymology).startsWith("gammeldansk")
        assertThat(word.audio).containsExactly("https://static.ordnet.dk/mp3/11002/11002240_1.mp3")

        // 5 top-level senses + the "1.a"/"2.a" sub-senses.
        assertThat(word.definitions).hasSize(7)
        val first = word.definitions[0]
        assertThat(first.senseNumber).isEqualTo("1.")
        assertThat(first.grammar).isEqualTo("substantiv, intetkøn")
        assertThat(first.pos).isEqualTo("substantiv, intetkøn")
        assertThat(first.glosses).hasSize(1)
        assertThat(first.glosses[0].definition).startsWith("fysisk eller")
        // The first sense's "Eksempler" box + sentence quotes.
        assertThat(first.glosses[0].examples).isNotEmpty()

        // Sub-senses keep their page numbers.
        assertThat(word.definitions.map { it.senseNumber })
            .containsExactly("1.", "1.a", "2.", "2.a", "3.", "4.", "5.")

        // The energy sense carries the FYSIK domain marker.
        val energy = word.definitions.find { it.glosses[0].definition.startsWith("den energi") }
        assertThat(energy).isNotNull()
        assertThat(energy?.domain).isEqualTo("FYSIK")

        // Synonyms resolved to full entry links (`?entry_id=...` against the
        // page url).
        val withSyns = word.definitions.firstOrNull { it.synonyms.isNotEmpty() }
        assertThat(withSyns).isNotNull()
        assertThat(withSyns?.synonyms?.get(0)?.href).startsWith("https://ordnet.dk/ddo/ordbog?entry_id=")

        // 8 fixed expressions + the "arbejde i noget" sub-sense shell.
        assertThat(word.idioms).hasSize(9)
        assertThat(word.idioms[0].idiom).isEqualTo("bestilt arbejde")
        assertThat(word.idioms[0].glosses).isNotEmpty()
        assertThat(word.idioms[0].glosses[0].definition).contains("arbejdsopgave")

        assertGolden(words, "../testdata/ddo/arbejde.json")
    }

    @Test
    fun testParseSearch() {
        val body = File("../testdata/ddo-search.json").readText()

        val results = DdoParser.parseSearch(body) { word ->
            httpUrl("https://ws.dsl.dk/ddo/query?app=android&version=2.1.5&q=$word")
        }

        assertThat(results).hasSize(7)
        assertThat(results[0].mTitle).isEqualTo("arbejde")
        assertThat(results[0].uri.toString())
            .isEqualTo("https://ws.dsl.dk/ddo/query?app=android&version=2.1.5&q=arbejde")
        assertThat(results[6].mTitle).isEqualTo("arbejdet")
    }

    @Test
    fun testParseSearchToleratesNonArrayBodies() {
        val results = DdoParser.parseSearch("{}") { word ->
            httpUrl("https://ws.dsl.dk/ddo/query?q=$word")
        }
        assertThat(results).isEmpty()
    }
}