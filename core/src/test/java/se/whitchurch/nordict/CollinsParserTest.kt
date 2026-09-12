package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the Collins Spanish-English parser. Maps parsed
 * `Word`s through the exact same `WordJson.toWordData()` mapping the desktop
 * CLI emits, so the app, the CLI, and the test suite all agree on one JSON
 * schema.
 */
class CollinsParserTest {

    private fun assertGolden(words: List<Word>, name: String) {
        Goldens.assertGolden(
            words.map { it.toWordData() },
            "../testdata/colspan/$name.json",
            Array<WordJson.WordData>::class.java
        )
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseFrente() {
        val htmlFile = File("../testdata/colspan/frente.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/frente")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(4)

        // Main dictionary headwords first: each POS-group hom is its own Word.
        val fem = words[0]
        assertThat(fem.mTitle).isEqualTo("frente")
        assertThat(fem.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(fem.audio).hasSize(2)
        assertThat(fem.audio[0]).contains("ES-419")
        assertThat(fem.audio[1]).contains("ES-ES")
        assertThat(fem.definitions).hasSize(1)

        // First main headword: feminine noun; idioms/phrases are nested in its
        // single sense and stay attached to the gloss, not the definition.
        assertThat(fem.definitions[0].pos).isEqualTo("feminine noun")
        assertThat(fem.definitions[0].glosses).hasSize(1)
        assertThat(fem.definitions[0].idioms).isEmpty()
        assertThat(fem.definitions[0].phrases).isEmpty()
        assertThat(fem.definitions[0].glosses[0].idioms).hasSize(4)
        assertThat(fem.definitions[0].glosses[0].phrases).hasSize(1)
        assertThat(fem.definitions[0].glosses[0].idioms[0].headword).isEqualTo("adornar la frente a alguien")

        // Second main headword: masculine noun. Its 9 phrases belong to the
        // senses they are nested in (e.g. "al frente" is part of definition 1),
        // not hoisted to the end of the POS group.
        val masc = words[1]
        assertThat(masc.mTitle).isEqualTo("frente")
        assertThat(masc.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(masc.definitions).hasSize(1)
        assertThat(masc.definitions[0].pos).isEqualTo("masculine noun")
        assertThat(masc.definitions[0].glosses).hasSize(6)
        assertThat(masc.definitions[0].idioms).isEmpty()
        assertThat(masc.definitions[0].phrases).isEmpty()
        assertThat(masc.definitions[0].glosses[0].phrases).hasSize(5)
        assertThat(masc.definitions[0].glosses[0].phrases[0].headword).isEqualTo("al frente")
        assertThat(masc.definitions[0].glosses[1].phrases).hasSize(1)
        assertThat(masc.definitions[0].glosses[1].phrases[0].headword).isEqualTo("de frente")
        assertThat(masc.definitions[0].glosses[2].phrases).hasSize(1)
        assertThat(masc.definitions[0].glosses[4].phrases).hasSize(1)
        assertThat(masc.definitions[0].glosses[4].phrases[0].headword).isEqualTo("frente a")
        assertThat(masc.definitions[0].glosses[5].phrases).hasSize(1)
        assertThat(masc.definitions[0].glosses[5].phrases[0].headword).isEqualTo("frente mío/suyo")

        // Main headwords keep the canonical URL for the first, __ref for the rest.
        assertThat(fem.uri.toString()).doesNotContain("__ref")
        assertThat(fem.xrefs).containsExactly("3")
        assertThat(masc.uri.toString()).contains("__ref=4")
        assertThat(masc.xrefs).containsExactly("4")

        // Every word on the page carries the full renderable entry list (main
        // headwords first, then easy-learning), itself included.
        for (w in words) {
            assertThat(w.mHomonymEntries).hasSize(4)
            assertThat(w.mHomonymEntries.map { it.ref }).containsExactly("3", "4", "1", "2").inOrder()
        }
        assertThat(fem.mHomonymEntries.map { it.mTitle })
            .containsExactly("frente", "frente", "la frente", "el frente").inOrder()
        assertThat(fem.mHomonymEntries[1].definitions[0].pos).isEqualTo("masculine noun")
        assertThat(fem.mHomonymEntries[1].audio).hasSize(2)
        assertThat(fem.mHomonymEntries[3].dictionary).isEqualTo("Collins Easy Learning")
        // Every entry snapshot carries its own content.
        assertThat(words[2].mHomonymEntries[2].mTitle).isEqualTo("la frente")

        // Easy-learning headwords follow (no audio).
        assertThat(words[2].mTitle).isEqualTo("la frente")
        assertThat(words[2].rawHeadword).isEqualTo("frente")
        assertThat(words[2].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[2].audio).isEmpty()
        assertThat(words[2].uri.toString()).contains("__ref=1")

        assertThat(words[3].mTitle).isEqualTo("el frente")
        assertThat(words[3].rawHeadword).isEqualTo("frente")
        assertThat(words[3].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[3].uri.toString()).contains("__ref=2")

        assertGolden(words, "frente")
    }

    @Test
    fun testParseCagar() {
        val htmlFile = File("../testdata/colspan/cagar.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/cagar")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(3)

        // intransitive verb: 1 idiom nested in its (single) sense
        val intr = words[0]
        assertThat(intr.mTitle).isEqualTo("cagar")
        assertThat(intr.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(intr.definitions).hasSize(1)
        assertThat(intr.definitions[0].pos).isEqualTo("intransitive verb")
        assertThat(intr.definitions[0].idioms).isEmpty()
        assertThat(intr.definitions[0].glosses[0].idioms).hasSize(1)
        assertThat(intr.definitions[0].glosses[0].idioms[0].headword).isEqualTo("¡está que no caga!")

        // transitive verb: 4 glosses, 3 idioms (nested in the second sense)
        val trans = words[1]
        assertThat(trans.mTitle).isEqualTo("cagar")
        assertThat(trans.definitions).hasSize(1)
        assertThat(trans.definitions[0].pos).isEqualTo("transitive verb")
        assertThat(trans.definitions[0].glosses).hasSize(4)
        assertThat(trans.definitions[0].idioms).isEmpty()
        assertThat(trans.definitions[0].glosses[1].idioms).hasSize(3)

        // reflexive verb: resolves to the zero-sense cagarse cross-ref
        val reflex = words[2]
        assertThat(reflex.mTitle).isEqualTo("cagar")
        assertThat(reflex.definitions).hasSize(1)
        assertThat(reflex.definitions[0].pos).isEqualTo("reflexive verb")
        assertThat(reflex.definitions[0].glosses).hasSize(1)

        assertGolden(words, "cagar")
    }

    @Test
    fun testParseMorir() {
        val htmlFile = File("../testdata/colspan/morir.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/morir")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(3)

        val intr = words[0]
        assertThat(intr.mTitle).isEqualTo("morir")
        assertThat(intr.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(intr.definitions).hasSize(1)
        assertThat(intr.definitions[0].pos).isEqualTo("intransitive verb")
        assertThat(intr.definitions[0].glosses).hasSize(2)
        assertThat(intr.definitions[0].idioms).isEmpty()
        assertThat(intr.definitions[0].phrases).isEmpty()
        assertThat(intr.definitions[0].glosses[0].examples).hasSize(4)
        assertThat(intr.definitions[0].glosses[0].idioms).hasSize(1)
        assertThat(intr.definitions[0].glosses[0].idioms[0].headword).isEqualTo("morir al pie del cañón")
        assertThat(intr.definitions[0].glosses[0].phrases).hasSize(5)
        assertThat(intr.definitions[0].glosses[1].idioms).hasSize(1)
        assertThat(intr.definitions[0].glosses[1].idioms[0].headword).isEqualTo("y allí muere")

        // single-sense reflexive cross-ref
        val reflex = words[1]
        assertThat(reflex.mTitle).isEqualTo("morir")
        assertThat(reflex.definitions).hasSize(1)
        assertThat(reflex.definitions[0].pos).isEqualTo("reflexive verb")
        assertThat(reflex.definitions[0].glosses).hasSize(1)

        // Easy-learning entry
        assertThat(words[2].mTitle).isEqualTo("morir")
        assertThat(words[2].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[2].definitions).hasSize(1)
        assertThat(words[2].definitions[0].pos).isEqualTo("verb")

        assertGolden(words, "morir")
    }

    @Test
    fun testParseMuerte() {
        val htmlFile = File("../testdata/colspan/muerte.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/muerte")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(2)

        val main = words[0]
        assertThat(main.mTitle).isEqualTo("muerte")
        assertThat(main.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(main.definitions).hasSize(1)
        assertThat(main.definitions[0].pos).isEqualTo("feminine noun")
        assertThat(main.definitions[0].glosses).hasSize(3)
        assertThat(main.definitions[0].idioms).isEmpty()
        assertThat(main.definitions[0].phrases).isEmpty()
        assertThat(main.definitions[0].glosses[0].idioms).hasSize(4)
        assertThat(main.definitions[0].glosses[0].phrases).hasSize(7)
        assertThat(main.definitions[0].glosses[1].phrases).hasSize(1)

        // Phrases of sense 1 include "una lucha a muerte"
        assertThat(main.definitions[0].glosses[0].phrases[0].headword).isEqualTo("una lucha a muerte")
        assertThat(main.definitions[0].glosses[0].phrases[0].examples).hasSize(4)

        // Easy-learning entry: the two roaming phrases stay at definition level.
        val easy = words[1]
        assertThat(easy.mTitle).isEqualTo("la muerte")
        assertThat(easy.rawHeadword).isEqualTo("muerte")
        assertThat(easy.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(easy.definitions[0].phrases).hasSize(2)

        assertGolden(words, "muerte")
    }

    @Test
    fun testParseOtro() {
        val htmlFile = File("../testdata/colspan/otro.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/otro")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(3)

        // adjective: 3 glosses; 5 phrases spread across the senses
        val adj = words[0]
        assertThat(adj.mTitle).isEqualTo("otro")
        assertThat(adj.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(adj.definitions).hasSize(1)
        assertThat(adj.definitions[0].pos).isEqualTo("adjective")
        assertThat(adj.definitions[0].glosses).hasSize(3)
        assertThat(adj.definitions[0].phrases).isEmpty()
        assertThat(adj.definitions[0].glosses[0].phrases).hasSize(3)
        assertThat(adj.definitions[0].glosses[1].phrases).hasSize(2)

        // pronoun: 4 glosses; 1 idiom and 3 phrases on the senses they belong to
        val pron = words[1]
        assertThat(pron.mTitle).isEqualTo("otro")
        assertThat(pron.definitions).hasSize(1)
        assertThat(pron.definitions[0].pos).isEqualTo("pronoun")
        assertThat(pron.definitions[0].glosses).hasSize(4)
        assertThat(pron.definitions[0].idioms).isEmpty()
        assertThat(pron.definitions[0].phrases).isEmpty()
        assertThat(pron.definitions[0].glosses[0].phrases).hasSize(2)
        assertThat(pron.definitions[0].glosses[3].idioms).hasSize(1)
        assertThat(pron.definitions[0].glosses[3].idioms[0].headword).isEqualTo("¡otro que tal (baila)!")
        assertThat(pron.definitions[0].glosses[3].phrases).hasSize(1)

        // Easy-learning entry: roaming phrases on the definition, two on sense 1
        assertThat(words[2].mTitle).isEqualTo("otro")
        assertThat(words[2].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[2].definitions).hasSize(1)
        assertThat(words[2].definitions[0].pos).isEqualTo("adjective or pronoun")
        assertThat(words[2].definitions[0].glosses).hasSize(2)
        assertThat(words[2].definitions[0].phrases).hasSize(6)
        assertThat(words[2].definitions[0].glosses[0].phrases).hasSize(2)

        assertGolden(words, "otro")
    }

    @Test
    fun testParseLeyDeLaGravedad() {
        val htmlFile = File("../testdata/colspan/ley+de+la+gravedad.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/ley-de-la-gravedad")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(2)

        // Cross-reference stub: the <div class="hom sense"> collapses the hom
        // and its single sense, so the translation is captured rather than lost.
        val stub = words[0]
        assertThat(stub.mTitle).isEqualTo("ley de la gravedad")
        assertThat(stub.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(stub.uri.toString()).doesNotContain("__ref")
        assertThat(stub.definitions).hasSize(1)
        assertThat(stub.definitions[0].pos).isEmpty()
        assertThat(stub.definitions[0].glosses).hasSize(1)
        assertThat(stub.definitions[0].glosses[0].definition).contains("law of gravity")

        // The embedded full "ley" entry (carrying only data-xrentry) becomes its
        // own main headword, reachable via __ref like any other homograph.
        val ley = words[1]
        assertThat(ley.mTitle).isEqualTo("ley")
        assertThat(ley.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(ley.uri.toString()).contains("__ref=2")
        assertThat(ley.definitions).hasSize(1)
        assertThat(ley.definitions[0].pos).isEqualTo("feminine noun")
        assertThat(ley.definitions[0].glosses).isNotEmpty()
        assertThat(ley.definitions[0].glosses[0].definition).contains("law")

        assertGolden(words, "ley")
    }

    @Test
    fun testParseSearch() {
        val body = File("../testdata/colspan-search.json").readText()

        val results = CollinsParser.parseSearch(body) { title ->
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/${title.replace(" ", "-").lowercase()}")
        }

        assertThat(results.map { it.mTitle })
            .containsExactly("cagar", "cagarse", "cagar(se)", "efectivo en caja").inOrder()
        assertThat(results[0].uri.toString())
            .isEqualTo("https://www.collinsdictionary.com/dictionary/spanish-english/cagar")
        assertThat(results[3].uri.toString())
            .isEqualTo("https://www.collinsdictionary.com/dictionary/spanish-english/efectivo-en-caja")
    }

    @Test
    fun testParseSearchToleratesNonArrayBodies() {
        val results = CollinsParser.parseSearch("[1, 2]") { title ->
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/$title")
        }

        assertThat(results).isEmpty()
    }
}