package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the DIDAC parser. Shares the exact `WordJson`
 * mapping and goldens the CLI emits, so the app, the desktop CLI, and the
 * test suite all agree on the same JSON.
 */
class DidacParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseCapSingleEntry() {
        val htmlFile = File("../testdata/didac/cap1.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.diccionari.cat/didac/cap1")
        val words = DidacParser.parse(page, uri, "DIDAC")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("cap")
        assertThat(word.rawHeadword).isEqualTo("cap")
        assertThat(word.uri.toString()).isEqualTo("https://www.diccionari.cat/didac/cap1")
        assertThat(word.definitions).hasSize(6)
        assertThat(word.idioms).hasSize(1)

        val def1 = word.definitions[0]
        assertThat(def1.grammar).isEqualTo("nom masculí")
        assertThat(def1.gender).isEqualTo(Genders.MASCULINE)
        assertThat(def1.glosses[0].definition).contains("El cap és la part de dalt del cos")

        // Mixed-gender grammar ("nom masculí i femení") has no gender class.
        val def3 = word.definitions[2]
        assertThat(def3.grammar).isEqualTo("nom masculí i femení")
        assertThat(def3.gender).isEqualTo("")

        // Definition with inline examples (the long <i> block) extracts them.
        val def4 = word.definitions[3]
        assertThat(def4.glosses[0].definition).contains("A vegades parlem del cap")
        assertThat(def4.glosses[0].examples).hasSize(1)
        assertThat(def4.glosses[0].examples[0]).contains("Has begut massa")

        // Sub-locutions inside regular definitions keep their bolded text.
        val def5 = word.definitions[4]
        assertThat(def5.glosses[0].definition).contains("El cap d'any és el primer dia de l'any")

        // "frase feta" items are idioms whose name is the bolded fragment.
        val idiom = word.idioms[0]
        assertThat(idiom.idiom).isEqualTo("fa cap")
        assertThat(idiom.grammar).isEqualTo("frase feta")
        assertThat(idiom.glosses[0].definition).isEqualTo(
            "Una persona o un camí a un lloc quan hi arriba o hi porta."
        )
        assertThat(idiom.glosses[0].examples).containsExactly("T'esperarem dins del bar; ja hi faràs cap.")

        assertGolden(words, "../testdata/didac/cap1.json")
    }

    @Test
    fun testParseCapSearchPage() {
        val htmlFile = File("../testdata/didac/cap.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.diccionari.cat/cerca/didac?search_api_fulltext_cust=cap&show=title")
        val words = DidacParser.parse(page, uri, "DIDAC")

        assertThat(words).hasSize(9)
        assertThat(words.map { it.mTitle })
            .containsExactly(
                "cap", "cap", "cap", "cap",
                "cap-roig", "al cap de", "pel cap alt", "pel cap baix", "al cap i a la fi"
            )
            .inOrder()
        assertThat(words.map { it.uri.toString() }).containsExactly(
            "https://www.diccionari.cat/didac/cap1",
            "https://www.diccionari.cat/didac/cap2",
            "https://www.diccionari.cat/didac/cap3",
            "https://www.diccionari.cat/didac/cap4",
            "https://www.diccionari.cat/didac/cap-roig",
            "https://www.diccionari.cat/didac/al-cap-de",
            "https://www.diccionari.cat/didac/pel-cap-alt",
            "https://www.diccionari.cat/didac/pel-cap-baix",
            "https://www.diccionari.cat/didac/al-cap-i-a-la-fi"
        ).inOrder()

        // Homograph navigation data is attached for multi-entry pages.
        assertThat(words[0].mHomonymEntries).hasSize(9)
        assertThat(words[0].mHomographs).hasSize(9)

        // cap4's "locució que fa d'adverbi" (cap al tard) is an idiom.
        val cap4 = words[3]
        assertThat(cap4.definitions).hasSize(3)
        assertThat(cap4.definitions[0].grammar).isEqualTo("preposició")
        assertThat(cap4.idioms).hasSize(1)
        assertThat(cap4.idioms[0].idiom).isEqualTo("cap al tard")
        assertThat(cap4.idioms[0].glosses[0].definition).isEqualTo("és després de post el sol.")

        // Flat entries ("nom masculí" + a single running gloss).
        val capRoig = words[4]
        assertThat(capRoig.definitions).hasSize(1)
        assertThat(capRoig.definitions[0].grammar).isEqualTo("nom masculí")
        assertThat(capRoig.definitions[0].glosses[0].definition)
            .isEqualTo("[Plural: també cap-rojos] escórpora.")

        // Flat locutions keep their definition and grammar.
        val alCapDe = words[5]
        assertThat(alCapDe.definitions[0].grammar).isEqualTo("locució que fa de preposició")
        assertThat(alCapDe.definitions[0].glosses[0].definition)
            .isEqualTo("Quan hagi passat un temps determinat.")
        assertThat(alCapDe.definitions[0].glosses[0].examples).hasSize(1)

        assertGolden(words, "../testdata/didac/cap.json")
    }

    @Test
    fun testParseSearch() {
        val body = File("../testdata/didac-search.json").readText()

        val results = DidacParser.parseSearch(body) { path ->
            httpUrl("https://www.diccionari.cat$path")
        }

        assertThat(results).hasSize(9)
        assertThat(results.map { it.mTitle }).isEqualTo(
            listOf("cap", "cap", "cap", "cap", "cap-roig", "al cap de", "pel cap alt", "pel cap baix", "al cap i a la fi")
        )
        assertThat(results[0].uri.toString()).isEqualTo("https://www.diccionari.cat/didac/cap1")
        assertThat(results[4].uri.toString()).isEqualTo("https://www.diccionari.cat/didac/cap-roig")
    }

    @Test
    fun testParseSearchToleratesNonArrayBodies() {
        val results = DidacParser.parseSearch("{}") { path ->
            httpUrl("https://www.diccionari.cat$path")
        }

        assertThat(results).isEmpty()
    }
}