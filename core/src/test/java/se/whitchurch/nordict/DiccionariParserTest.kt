package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the diccionari.cat parser shared by the GDLC,
 * catala-castella (CA-ES) and catala-angles (CA-EN) dictionaries. Shares the
 * exact `WordJson` mapping and goldens the CLI emits, so the app, the desktop
 * CLI, and the test suite all agree on the same JSON.
 */
class DiccionariParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    private fun parse(
        file: String,
        uri: String,
        tag: String,
        nodeClass: String,
        bilingual: Boolean
    ): List<Word> {
        val page = File("../testdata/$file").readText()
        return DiccionariParser.parse(page, httpUrl(uri), tag, nodeClass, bilingual)
    }

    // ---- GDLC (monolingual) ----

    @Test
    fun testParseGdlcCapFull() {
        val words = parse(
            "gdlc/cap1.html", "https://www.diccionari.cat/GDLC/cap1", "GDLC", "diccionari-gdlc", false
        )

        assertThat(words).hasSize(1)
        val word = words[0]
        assertThat(word.mTitle).isEqualTo("cap")
        assertThat(word.uri.toString()).isEqualTo("https://www.diccionari.cat/GDLC/cap1")
        assertThat(word.definitions).hasSize(27)
        assertThat(word.idioms).hasSize(137)

        val def0 = word.definitions[0]
        assertThat(def0.grammar).isEqualTo("masculí")
        assertThat(def0.gender).isEqualTo(Genders.MASCULINE)
        assertThat(def0.domain).isEqualTo("anatomia")
        assertThat(def0.glosses[0].definition).contains(
            "Part superior del cos de l’home i anterior i superior de molts animals"
        )

        // Usage examples are sentence-length <i> blocks extracted from the copy.
        val def3 = word.definitions[3]
        assertThat(def3.glosses[0].examples).containsExactly("Tenir cap. Tenir molt de cap.")

        // Mixed-gender grammar has no gender class.
        val def4 = word.definitions[4]
        assertThat(def4.grammar).isEqualTo("masculí i femení")
        assertThat(def4.gender).isEqualTo("")

        // A locution <li> opening with a bolded fragment is an idiom; the
        // running text after the bold is its gloss. One of the "cap de mort"
        // entries carries two senses inside a nested <ol> (the locution
        // wrapper), the rest are single-gloss.
        val capDeMort = word.idioms.single { it.glosses.size == 2 }
        assertThat(capDeMort.idiom).isEqualTo("cap de mort")
        assertThat(capDeMort.glosses).hasSize(2)

        // The locution "cap de cavall" has no gender on its sense group.
        val capDeCavall = word.idioms.first { it.idiom == "cap de cavall" }
        assertThat(capDeCavall.gender).isEqualTo("")
        assertThat(capDeCavall.domain).isEqualTo("indústria tèxtil")

        assertGolden(words, "../testdata/gdlc/cap1.json")
    }

    @Test
    fun testParseGdlcCapSearch() {
        val words = parse(
            "gdlc/cap.html",
            "https://www.diccionari.cat/cerca/GDLC?search_api_fulltext_cust=cap&show=title",
            "GDLC", "diccionari-gdlc", false
        )

        assertThat(words).hasSize(10)
        assertThat(words.map { it.mTitle }).containsExactly(
            "cap.", "cap", "cap", "cap",
            "cap-rossenc", "cap-banda", "cap-barra", "cap-pal", "cap-rodo", "cap-roig"
        ).inOrder()
        assertThat(words.map { it.uri.toString() }).containsExactly(
            "https://www.diccionari.cat/GDLC/cap",
            "https://www.diccionari.cat/GDLC/cap1",
            "https://www.diccionari.cat/GDLC/cap2",
            "https://www.diccionari.cat/GDLC/cap3",
            "https://www.diccionari.cat/GDLC/cap-rossenc",
            "https://www.diccionari.cat/GDLC/cap-banda",
            "https://www.diccionari.cat/GDLC/cap-barra",
            "https://www.diccionari.cat/GDLC/cap-pal",
            "https://www.diccionari.cat/GDLC/cap-rodo",
            "https://www.diccionari.cat/GDLC/cap-roig"
        ).inOrder()

        assertThat(words[0].mHomonymEntries).hasSize(10)
        assertThat(words[0].mHomographs).hasSize(10)

        // The "cap1" article in the search view matches the full-view parse.
        val cap1 = words[1]
        assertThat(cap1.definitions).hasSize(27)
        assertThat(cap1.idioms).hasSize(137)
        assertThat(cap1.definitions[0].domain).isEqualTo("anatomia")

        // Flat entries usually have a single running gloss; cap-roig carries
        // two sense groups (ictiologia, ornitologia) with no nested <ol>.
        val capRoig = words[9]
        assertThat(capRoig.definitions).hasSize(2)
        assertThat(capRoig.definitions[0].domain).isEqualTo("ictiologia")
        assertThat(capRoig.definitions[1].domain).isEqualTo("ornitologia")

        assertGolden(words, "../testdata/gdlc/cap.json")
    }

    // ---- CA-ES (bilingual) ----

    @Test
    fun testParseCaEsTaula() {
        val words = parse(
            "ca-es/taula.html", "https://www.diccionari.cat/catala-castella/taula", "CA-ES", "diccionari-ca-es", true
        )

        assertThat(words).hasSize(1)
        val word = words[0]
        assertThat(word.mTitle).isEqualTo("taula")
        assertThat(word.definitions).hasSize(12)
        assertThat(word.idioms).hasSize(28)

        // Short <i> article markers (m, f, sing) stay in the running copy...
        val def3 = word.definitions[3]
        assertThat(def3.glosses[0].definition).isEqualTo(
            "[de fusta, marbre, etc] tabla, tablero <i>m</i>."
        )
        // ...while a usage example paired with its translation is extracted.
        val def1 = word.definitions[1]
        assertThat(def1.glosses[0].examples).containsExactly(
            "La bona taula és sovint danyosa per a la salut, la buena mesa es con frecuencia dañina para la salud."
        )
        assertThat(def1.glosses[0].definition).doesNotContain("<i>La bona taula")

        // Idioms reuse the group grammar and pull register/dom markers.
        val aTaulaFranca = word.idioms.first { it.idiom == "a taula franca" }
        assertThat(aTaulaFranca.grammar).isEqualTo("femení")
        assertThat(aTaulaFranca.register).isEqualTo("familiarment")

        // Parenthesized "o" connectors stay as inline <i> variants.
        val desparar = word.idioms.first { it.idiom == "desparar la taula" }
        assertThat(desparar.glosses[0].definition)
            .isEqualTo("quitar (<i>o</i> alzar, <i>o</i>levantar) la mesa.")

        assertGolden(words, "../testdata/ca-es/taula.json")
    }

    @Test
    fun testParseCaEsCapSearch() {
        val words = parse(
            "ca-es/cap.html",
            "https://www.diccionari.cat/cerca/ca-es?search_api_fulltext_cust=cap&show=title",
            "CA-ES", "diccionari-ca-es", true
        )

        assertThat(words).hasSize(9)
        assertThat(words.map { it.mTitle }).containsExactly(
            "cap", "cap", "cap", "Canaveral, cap", "Cap Verd",
            "cap-roig", "cap-rossenc", "cap-xebró", "Cap, Ciutat del"
        ).inOrder()

        // Homonym refs are sequential even when titles collide.
        assertThat(words.map { it.xrefs.single() }).containsExactly(
            "1", "2", "3", "4", "5", "6", "7", "8", "9"
        ).inOrder()

        // The cap1 article in this search page reliably exercises the
        // bilingual example pairing.
        assertThat(words[0].definitions[0].glosses[0].examples)
            .contains("Donar-se un cop al cap, darse un golpe en la cabeza.")

        assertGolden(words, "../testdata/ca-es/cap.json")
    }

    // ---- CA-EN (bilingual) ----

    @Test
    fun testParseCaEnTaula() {
        val words = parse(
            "ca-en/taula.html", "https://www.diccionari.cat/catala-angles/taula", "CA-EN", "diccionari-ca-en", true
        )

        assertThat(words).hasSize(1)
        val word = words[0]
        assertThat(word.mTitle).isEqualTo("taula")
        assertThat(word.pronunciation).isEqualTo("táwlə")
        assertThat(word.definitions).hasSize(14)
        assertThat(word.idioms).hasSize(21)

        // "femení plural" carries the feminine gender class.
        val def8 = word.definitions[8]
        assertThat(def8.grammar).isEqualTo("femení plural")
        assertThat(def8.gender).isEqualTo(Genders.FEMININE)

        val registre = word.idioms.first { it.idiom == "fer taula rasa" }
        assertThat(registre.register).isEqualTo("figuradament")

        assertGolden(words, "../testdata/ca-en/taula.json")
    }

    @Test
    fun testParseCaEnCapSearch() {
        val words = parse(
            "ca-en/cap.html",
            "https://www.diccionari.cat/cerca/ca-en?search_api_fulltext_cust=cap&show=title",
            "CA-EN", "diccionari-ca-en", true
        )

        assertThat(words).hasSize(2)
        assertThat(words.map { it.mTitle }).containsExactly("cap", "cap-rossenc").inOrder()
        assertThat(words.map { it.pronunciation }).containsExactly("káp", "kàbrusɛ́ŋ").inOrder()
        assertThat(words[0].mHomonymEntries).hasSize(2)

        assertGolden(words, "../testdata/ca-en/cap.json")
    }

    // ---- search results (autocomplete) ----

    @Test
    fun testParseSearch() {
        val results = DiccionariParser.parseSearch(
            File("../testdata/gdlc-search.json").readText(),
            "GDLC"
        ) { path ->
            httpUrl("https://www.diccionari.cat$path")
        }
        assertThat(results).hasSize(10)
        assertThat(results.map { it.mTitle }).isEqualTo(
            listOf("cap.", "cap", "cap", "cap", "cap-banda", "cap-barra", "cap-pal", "cap-rodo", "cap-roig", "cap-rossenc")
        )
        assertThat(results[0].uri.toString()).isEqualTo("https://www.diccionari.cat/GDLC/cap")
        assertThat(results[1].uri.toString()).isEqualTo("https://www.diccionari.cat/GDLC/cap1")

        // The autocomplete payload mixes indexed entries (with a URL) and
        // prefix-completion suggestions (no URL): "taula" is the indexed
        // entry; "taulalla", "tauladora", "taulaplom" and "taulat" are
        // completions resolved into this dictionary's entry URL space. The
        // query-echo trailer ("taula", no suffix span) is dropped.
        val caEs = DiccionariParser.parseSearch(
            File("../testdata/ca-es-search.json").readText(),
            "catala-castella"
        ) { path ->
            httpUrl("https://www.diccionari.cat$path")
        }
        assertThat(caEs).hasSize(5)
        assertThat(caEs.map { it.mTitle }).isEqualTo(
            listOf("taula", "taulalla", "tauladora", "taulaplom", "taulat")
        )
        assertThat(caEs[0].uri.toString()).isEqualTo("https://www.diccionari.cat/catala-castella/taula")
        assertThat(caEs[3].uri.toString()).isEqualTo("https://www.diccionari.cat/catala-castella/taulaplom")

        val caEn = DiccionariParser.parseSearch(
            File("../testdata/ca-en-search.json").readText(),
            "catala-angles"
        ) { path ->
            httpUrl("https://www.diccionari.cat$path")
        }
        assertThat(caEn).hasSize(6)
        assertThat(caEn.map { it.mTitle }).isEqualTo(
            listOf("taula", "tauladora", "taulada", "taulalla", "taulaplom", "taulat")
        )
        assertThat(caEn[0].uri.toString()).isEqualTo("https://www.diccionari.cat/catala-angles/taula")
    }

    @Test
    fun testParseSearchToleratesNonArrayBodies() {
        val results = DiccionariParser.parseSearch("{}", "catala-castella") { path ->
            httpUrl("https://www.diccionari.cat$path")
        }
        assertThat(results).isEmpty()
    }
}