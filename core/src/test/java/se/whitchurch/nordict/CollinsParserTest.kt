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

        // Easy-learning headwords first, then main dictionary headwords: each
        // POS-group hom is its own Word.
        val easyLa = words[0]
        assertThat(easyLa.mTitle).isEqualTo("la frente")
        assertThat(easyLa.rawHeadword).isEqualTo("frente")
        assertThat(easyLa.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(easyLa.audio).isEmpty()
        assertThat(easyLa.uri.toString()).doesNotContain("__ref")
        assertThat(easyLa.xrefs).containsExactly("1")

        val easyEl = words[1]
        assertThat(easyEl.mTitle).isEqualTo("el frente")
        assertThat(easyEl.rawHeadword).isEqualTo("frente")
        assertThat(easyEl.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(easyEl.uri.toString()).contains("__ref=2")
        assertThat(easyEl.xrefs).containsExactly("2")

        val fem = words[2]
        assertThat(fem.mTitle).isEqualTo("frente")
        assertThat(fem.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(fem.audio).hasSize(1)
        assertThat(fem.audio[0]).contains("ES-ES")
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
        val masc = words[3]
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

        // First main headword keeps its __ref (only the first easy entry is canonical).
        assertThat(fem.uri.toString()).contains("__ref=3")
        assertThat(fem.xrefs).containsExactly("3")
        assertThat(masc.uri.toString()).contains("__ref=4")
        assertThat(masc.xrefs).containsExactly("4")

        // Every word on the page carries the full renderable entry list (easy-
        // learning first, then main headwords), itself included.
        for (w in words) {
            assertThat(w.mHomonymEntries).hasSize(4)
            assertThat(w.mHomonymEntries.map { it.ref }).containsExactly("1", "2", "3", "4").inOrder()
        }
        assertThat(fem.mHomonymEntries.map { it.mTitle })
            .containsExactly("la frente", "el frente", "frente", "frente").inOrder()
        assertThat(fem.mHomonymEntries[3].definitions[0].pos).isEqualTo("masculine noun")
        assertThat(fem.mHomonymEntries[3].audio).hasSize(1)
        assertThat(fem.mHomonymEntries[0].dictionary).isEqualTo("Collins Easy Learning")
        // Every entry snapshot carries its own content.
        assertThat(words[2].mHomonymEntries[2].mTitle).isEqualTo("frente")

        // Easy-learning headwords lead (first is canonical, no audio).
        assertThat(words[0].mTitle).isEqualTo("la frente")
        assertThat(words[0].rawHeadword).isEqualTo("frente")
        assertThat(words[0].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[0].audio).isEmpty()
        assertThat(words[0].uri.toString()).doesNotContain("__ref")

        assertThat(words[1].mTitle).isEqualTo("el frente")
        assertThat(words[1].rawHeadword).isEqualTo("frente")
        assertThat(words[1].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[1].uri.toString()).contains("__ref=2")

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

        // Easy-learning entry first.
        val easyMorir = words[0]
        assertThat(easyMorir.mTitle).isEqualTo("morir")
        assertThat(easyMorir.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(easyMorir.definitions).hasSize(1)
        assertThat(easyMorir.definitions[0].pos).isEqualTo("verb")
        assertThat(easyMorir.uri.toString()).doesNotContain("__ref")

        val intr = words[1]
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
        val reflex = words[2]
        assertThat(reflex.mTitle).isEqualTo("morir")
        assertThat(reflex.definitions).hasSize(1)
        assertThat(reflex.definitions[0].pos).isEqualTo("reflexive verb")
        assertThat(reflex.definitions[0].glosses).hasSize(1)

        assertGolden(words, "morir")
    }

    @Test
    fun testParseMuerte() {
        val htmlFile = File("../testdata/colspan/muerte.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/muerte")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(2)

        // Easy-learning entry first: the two roaming phrases stay at definition level.
        val easy = words[0]
        assertThat(easy.mTitle).isEqualTo("la muerte")
        assertThat(easy.rawHeadword).isEqualTo("muerte")
        assertThat(easy.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(easy.definitions[0].phrases).hasSize(2)
        assertThat(easy.uri.toString()).doesNotContain("__ref")

        val main = words[1]
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
        assertThat(main.uri.toString()).contains("__ref=2")

        assertGolden(words, "muerte")
    }

    @Test
    fun testParseOtro() {
        val htmlFile = File("../testdata/colspan/otro.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/otro")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(3)

        // Easy-learning entry first: roaming phrases on the definition, two on sense 1
        val easyOtro = words[0]
        assertThat(easyOtro.mTitle).isEqualTo("otro")
        assertThat(easyOtro.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(easyOtro.definitions).hasSize(1)
        assertThat(easyOtro.definitions[0].pos).isEqualTo("adjective or pronoun")
        assertThat(easyOtro.definitions[0].glosses).hasSize(2)
        assertThat(easyOtro.definitions[0].phrases).hasSize(6)
        assertThat(easyOtro.definitions[0].glosses[0].phrases).hasSize(2)

        // adjective: 3 glosses; 5 phrases spread across the senses
        val adj = words[1]
        assertThat(adj.mTitle).isEqualTo("otro")
        assertThat(adj.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(adj.definitions).hasSize(1)
        assertThat(adj.definitions[0].pos).isEqualTo("adjective")
        assertThat(adj.definitions[0].glosses).hasSize(3)
        assertThat(adj.definitions[0].phrases).isEmpty()
        assertThat(adj.definitions[0].glosses[0].phrases).hasSize(3)
        assertThat(adj.definitions[0].glosses[1].phrases).hasSize(2)

        // pronoun: 4 glosses; 1 idiom and 3 phrases on the senses they belong to
        val pron = words[2]
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
    fun testParseFeble() {
        val htmlFile = File("../testdata/colspan/feble.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/feble")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(1)

        // Only a Latin American (ES-419) clip exists for this word — with no
        // Spain alternative, the single available audio is kept as a fallback.
        val feble = words[0]
        assertThat(feble.mTitle).isEqualTo("feble")
        assertThat(feble.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(feble.audio).hasSize(1)
        assertThat(feble.audio[0]).contains("ES-419")

        assertGolden(words, "feble")
    }

    @Test
    fun testParsePocima() {
        val htmlFile = File("../testdata/colspan/pócima.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/p%C3%B3cima")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(1)

        // Two Spain clips: the lower-cased "es_es_pocima.mp3" spelling variant
        // plus the ES-ES- upper-cased main one — both kept (the lowercase
        // variant must not be dropped by a case-sensitive match).
        val pocima = words[0]
        assertThat(pocima.mTitle).isEqualTo("pócima")
        assertThat(pocima.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(pocima.audio).hasSize(2)
        assertThat(pocima.audio[0]).isEqualTo("https://www.collinsdictionary.com/sounds/hwd_sounds/es_es_pocima.mp3")
        assertThat(pocima.audio[1]).isEqualTo("https://www.collinsdictionary.com/sounds/hwd_sounds/ES-ES-W0216377.mp3")

        assertGolden(words, "pócima")
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