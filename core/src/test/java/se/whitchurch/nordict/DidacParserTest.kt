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
        val htmlFile = Goldens.fixture("../testdata/didac/cap1.html")
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

        // Sub-locutions inside regular definitions keep their bolded text,
        // e.g. "cap d'any" and "cap de setmana".
        val def5 = word.definitions[4]
        assertThat(def5.glosses[0].definition)
            .isEqualTo("El <b>cap d'any</b> és el primer dia de l'any.")
        val def6 = word.definitions[5]
        assertThat(def6.glosses[0].definition)
            .isEqualTo(
                "El <b>cap de setmana</b> és el dissabte i el diumenge. Si no es treballa, es pot descansar, passejar, llegir o fer esport."
            )

        // "frase feta" items are idioms whose name is the bolded fragment.
        // Mid-sentence bold ("fa cap") stays in the running copy.
        val idiom = word.idioms[0]
        assertThat(idiom.idiom).isEqualTo("fa cap")
        assertThat(idiom.grammar).isEqualTo("frase feta")
        assertThat(idiom.glosses[0].definition).isEqualTo(
            "Una persona o un camí <b>fa cap</b> a un lloc quan hi arriba o hi porta."
        )
        assertThat(idiom.glosses[0].examples).containsExactly("T'esperarem dins del bar; ja hi faràs cap.")

        assertGolden(words, "../testdata/didac/cap1.json")
    }

    @Test
    fun testParseCapSearchPage() {
        val htmlFile = Goldens.fixture("../testdata/didac/cap.html")
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

        // cap4's "locució que fa d'adverbi" (cap al tard) is an idiom. Its
        // bolded phrase opens the gloss, so it is dropped (it duplicates the
        // idiom name shown above) while mid-sentence bold is kept.
        val cap4 = words[3]
        assertThat(cap4.definitions).hasSize(3)
        assertThat(cap4.definitions[0].grammar).isEqualTo("preposició")
        assertThat(cap4.idioms).hasSize(1)
        assertThat(cap4.idioms[0].idiom).isEqualTo("cap al tard")
        assertThat(cap4.idioms[0].glosses[0].definition).isEqualTo("és després de post el sol.")

        // Flat entries ("nom masculí" + a single running gloss). The plural
        // note's <i> is emphasis and stays italic in the copy.
        val capRoig = words[4]
        assertThat(capRoig.definitions).hasSize(1)
        assertThat(capRoig.definitions[0].grammar).isEqualTo("nom masculí")
        assertThat(capRoig.definitions[0].glosses[0].definition)
            .isEqualTo("[Plural: també <i>cap-rojos</i>] escórpora.")

        // Flat locutions keep their definition and grammar.
        val alCapDe = words[5]
        assertThat(alCapDe.definitions[0].grammar).isEqualTo("locució que fa de preposició")
        assertThat(alCapDe.definitions[0].glosses[0].definition)
            .isEqualTo("Quan hagi passat un temps determinat.")
        assertThat(alCapDe.definitions[0].glosses[0].examples).hasSize(1)

        assertGolden(words, "../testdata/didac/cap.json")
    }

    @Test
    fun testParsePersona() {
        val htmlFile = Goldens.fixture("../testdata/didac/persona.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.diccionari.cat/didac/persona")
        val words = DidacParser.parse(page, uri, "DIDAC")

        assertThat(words).hasSize(1)
        val word = words[0]
        assertThat(word.mTitle).isEqualTo("persona")

        // Def 1: the long closing <i> sentence is an example.
        val def1 = word.definitions[0]
        assertThat(def1.glosses[0].definition).contains("Una persona és un ésser humà")
        assertThat(def1.glosses[0].examples).hasSize(1)
        assertThat(def1.glosses[0].examples[0]).contains("Hi ha vint persones a la sala")

        // Def 2: short mid-sentence <i> ("Jo canto", "Tu cantes", "Ella canta")
        // is emphasis, not examples: it must stay in the copy and in italics.
        val def2 = word.definitions[1]
        assertThat(def2.glosses[0].definition).isEqualTo(
            "En gramàtica, la persona d'un verb o d'un pronom pot ser primera, si es refereix a qui " +
                "parla; segona, si es refereix a qui escolta; i tercera, si es refereix a la persona de qui " +
                "es parla. <i>Jo canto</i> és primera persona. <i>Tu cantes</i> és segona persona. " +
                "<i>Ella canta</i> és tercera persona."
        )
        assertThat(def2.glosses[0].examples).isEmpty()

        // "locució que fa d'adverbi" idiom: mid-sentence bold stays in the copy.
        val idiom = word.idioms[0]
        assertThat(idiom.idiom).isEqualTo("en persona")
        assertThat(idiom.glosses[0].definition).isEqualTo(
            "Si fem una cosa <b>en persona</b> ho fem nosaltres mateixos, i no a través d'un altre."
        )
        assertThat(idiom.glosses[0].examples).hasSize(1)

        assertGolden(words, "../testdata/didac/persona.json")
    }

    @Test
    fun testParseSearch() {
        val body = Goldens.fixtureText("../testdata/didac-search.json")

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