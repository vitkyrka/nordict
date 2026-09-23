package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the EST parser. Maps parsed `Word`s through the
 * exact same `WordJson.toWordData()` mapping the desktop CLI emits, so the app,
 * the CLI, and the test suite all agree on one JSON schema.
 */
class EstParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    private fun synonyms(word: Word, index: Int): List<WordJson.SynonymData> =
        word.definitions[index].synonyms.map { WordJson.SynonymData(it.text, it.href, it.plev) }

    @Test
    fun testParseEst() {
        val htmlFile = Goldens.fixture("../testdata/est.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.rae.es/diccionario-estudiante/frente")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("frente")
        assertThat(word.definitions).hasSize(6)
        // One Idiom per .fc header (the original groups N numbered aceps
        // under one headword): 9 headers on the frente page.
        assertThat(word.idioms).hasSize(9)
        assertThat(word.idioms.map { it.idiom }).containsExactly(
            "al frente",
            "con la frente muy alta",
            "de frente",
            "en frente",
            "frente a",
            "frente a frente",
            "frente por frente",
            "hacer frente (a alguien o algo)",
            "llevarlo, o traerlo, alguien escrito en la frente"
        ).inOrder()

        // Definitions carry their span.orden numbers.
        assertThat(word.definitions.map { it.senseNumber })
            .containsExactly("1", "2", "3", "4", "5", "6").inOrder()

        // Grouped senses keep their per-acep numbers on the first gloss of
        // each acep; secondary .defP glosses stay unnumbered.
        val alFrente = word.idioms[0]
        assertThat(alFrente.glosses.map { it.senseNumber })
            .containsExactly("1", "2", "3").inOrder()
        val deFrente = word.idioms[2]
        assertThat(deFrente.glosses.map { it.definition }).contains("Sin desviar la vista.")
        assertThat(deFrente.glosses.map { it.senseNumber })
            .containsExactly("1", "", "2", "3", "4", "").inOrder()

        assertGolden(words, "../testdata/est.json")
    }

    @Test
    fun testParseEstCagar() {
        val htmlFile = Goldens.fixture("../testdata/est/cagar.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.rae.es/diccionario-estudiante/cagar")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("cagar")
        assertThat(word.definitions).hasSize(4)
        assertThat(word.idioms).hasSize(3)

        assertThat(word.definitions.map { it.plev }).containsExactly(
            "malsonante", "malsonante", "malsonante", "malsonante"
        )
        assertThat(word.idioms.map { it.plev }).containsExactly(
            "malsonante", "malsonante", "malsonante"
        )

        assertThat(word.definitions[0].grammar).isEqualTo("verbo intransitivo")
        assertThat(word.idioms[0].grammar).isEqualTo("locución verbal")

        // Defs 3 and 4 are pronominal; their glosses carry "cagarse" as headword
        assertThat(word.definitions[0].glosses[0].headword).isEqualTo("")
        assertThat(word.definitions[1].glosses[0].headword).isEqualTo("")
        assertThat(word.definitions[2].glosses[0].headword).isEqualTo("cagarse")
        assertThat(word.definitions[3].glosses[0].headword).isEqualTo("cagarse")

        assertThat(word.definitions.map { it.senseNumber })
            .containsExactly("1", "2", "3", "4").inOrder()

        assertGolden(words, "../testdata/est/cagar.json")
    }

    @Test
    fun testParseEstMorir() {
        val htmlFile = Goldens.fixture("../testdata/est/morir.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.rae.es/diccionario-estudiante/morir")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("morir")
        assertThat(word.conjugation).isEqualTo("dormir")
        assertThat(word.participle).isEqualTo("muerto")
        assertThat(word.definitions).hasSize(6)
        assertThat(word.idioms).hasSize(1)

// Definition 1: Dejar de vivir. — secondary gloss "También prnl."
        val def1 = word.definitions[0]
        assertThat(def1.glosses).hasSize(2)
        assertThat(def1.glosses[0].grammar).isEqualTo("verbo intransitivo")
        assertThat(def1.glosses[0].definition).isEqualTo("Dejar de vivir.")
        assertThat(def1.glosses[0].examples).containsExactly("Ha muerto en un accidente.")
        assertThat(def1.glosses[1].grammar).isEqualTo("")
        assertThat(def1.glosses[1].definition).isEqualTo("También prnl.")
        assertThat(def1.glosses[1].examples).containsExactly("Se ha muerto de un ataque al corazón.")
        assertThat(synonyms(word, 0)).containsExactly(
            WordJson.SynonymData("expirar", "https://www.rae.es/diccionario-estudiante/expirar", "")
        )
        // Def 1 is non-pronominal on its primary gloss; the "También prnl."
        // secondary gloss grammar is empty, so neither gets a headword.
        assertThat(def1.glosses[0].headword).isEqualTo("")
        assertThat(def1.glosses[1].headword).isEqualTo("")

        // Definition 2: Llegar algo a su fin. — secundary gloss "También prnl."
        val def2 = word.definitions[1]
        assertThat(def2.glosses).hasSize(2)
        assertThat(def2.glosses[0].grammar).isEqualTo("verbo intransitivo")
        assertThat(def2.glosses[0].definition).contains("Llegar")
        assertThat(def2.glosses[0].definition).contains("a su fin.")
        assertThat(def2.glosses[0].examples).containsExactly("El río muere en esta laguna.")
        assertThat(def2.glosses[1].definition).isEqualTo("También prnl.")
        assertThat(def2.glosses[1].examples).containsExactly("El fuego está a punto de morirse.")

        // Definition 3: Sentir intensamente algo. (coloquial)
        assertThat(word.definitions[2].glosses).hasSize(1)
        assertThat(word.definitions[2].glosses[0].grammar).isEqualTo("verbo intransitivo pronominal")
        assertThat(word.definitions[2].glosses[0].headword).isEqualTo("morirse")
        assertThat(word.definitions[2].register).isEqualTo("coloquial")
        assertThat(word.definitions[2].glosses[0].definition).isEqualTo("Sentir intensamente algo.")
        assertThat(word.definitions[2].glosses[0].examples).containsExactly(
            "Se muere de ganas de verte.",
            "Estoy muerto de hambre."
        )

        // Definition 4: Reírse mucho. — example belongs to the "Frec. morirse de risa" gloss
        val def4 = word.definitions[3]
        assertThat(def4.glosses).hasSize(2)
        assertThat(def4.glosses[0].grammar).isEqualTo("verbo intransitivo pronominal")
        assertThat(def4.glosses[0].definition).isEqualTo("Reírse mucho.")
        assertThat(def4.glosses[0].examples).isEmpty()
        assertThat(def4.glosses[1].definition).isEqualTo("Frecuentemente morirse de risa.")
        assertThat(def4.glosses[1].examples).containsExactly(
            "Cuenta unas historias para morirse de risa."
        )
        assertThat(def4.register).isEqualTo("coloquial")

        // Definition 5: Amar intensamente a alguien. (coloquial)
        assertThat(word.definitions[4].glosses).hasSize(1)
        assertThat(word.definitions[4].glosses[0].grammar).isEqualTo("verbo intransitivo pronominal")
        assertThat(word.definitions[4].glosses[0].definition).isEqualTo("Amar intensamente a alguien.")
        assertThat(word.definitions[4].glosses[0].examples).containsExactly(
            "Le dijo que se moría por ella."
        )

        // Definition 6: Desear vehementemente algo. (coloquial)
        assertThat(word.definitions[5].glosses).hasSize(1)
        assertThat(word.definitions[5].glosses[0].grammar).isEqualTo("verbo intransitivo pronominal")
        assertThat(word.definitions[5].glosses[0].definition).isEqualTo("Desear vehementemente algo.")
        assertThat(word.definitions[5].glosses[0].examples).containsExactly(
            "Se muere por conocerlo."
        )

        // Idiom: muera — three glosses, each example owned by its defP gloss
        val muera = word.idioms[0]
        assertThat(muera.idiom).isEqualTo("muera")
        assertThat(muera.glosses).hasSize(3)
        assertThat(muera.glosses[0].grammar).isEqualTo("expresión")
        assertThat(muera.glosses[0].definition).contains("expresar rechazo u odio")
        assertThat(muera.glosses[0].examples).isEmpty()
        assertThat(muera.glosses[1].grammar).isEqualTo("")
        assertThat(muera.glosses[1].definition).isEqualTo("Se usa especialmente como grito de protesta.")
        assertThat(muera.glosses[1].examples).containsExactly("Los republicanos gritaban: –¡Muera la monarquía!")
        // Grammar carried by the "Tb. m." defP abbr title
        assertThat(muera.glosses[2].definition).isEqualTo("También nombre masculino")
        assertThat(muera.glosses[2].grammar).isEqualTo("nombre masculino")
        assertThat(muera.glosses[2].gender).isEqualTo(Genders.MASCULINE)
        assertThat(muera.glosses[2].examples).containsExactly("Los mueras contra el general ahogaban los vítores de sus partidarios.")

        assertGolden(words, "../testdata/est/morir.json")
    }

    @Test
    fun testParseEstMuerte() {
        val htmlFile = Goldens.fixture("../testdata/est/muerte.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.rae.es/diccionario-estudiante/muerte")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(3)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("muerte")
        // 3 main definitions; the .sols sub-entries are separate headwords
        assertThat(word.definitions).hasSize(3)
        // One Idiom per .fc header: the two "a muerte" aceps group into one.
        assertThat(word.idioms).hasSize(6)
        assertThat(word.conjugation).isEmpty()
        assertThat(word.participle).isEmpty()
        assertThat(word.xrefs).containsExactly("1")

        // Definition 1: synonym from a relative <a class="synon" href="...">
        val def1 = word.definitions[0]
        assertThat(def1.glosses[0].definition).isEqualTo(
            "Término de la vida de una persona o de otro ser vivo."
        )
        assertThat(synonyms(word, 0)).containsExactly(
            WordJson.SynonymData("defunción", "https://www.rae.es/diccionario-estudiante/defunción", "")
        )

        // Regression: every idiom must be a real acep, not the <a class="acep">
        // cross-reference anchors in the definition text (which produced ghost,
        // zero-gloss duplicates). "la Muerte" must appear exactly once.
        assertThat(word.idioms.map { it.glosses.size }).containsExactly(1, 5, 1, 1, 2, 1)
        assertThat(word.idioms.map { it.idiom }).containsExactly(
            "a la muerte",
            "a muerte",
            "dar muerte (a alguien)",
            "de mala muerte",
            "de muerte",
            "la Muerte"
        ).inOrder()

        // The grouped "a muerte" keeps both aceps' numbers on their first
        // glosses; secondary .defP glosses stay unnumbered.
        val aMuerte = word.idioms[1]
        assertThat(aMuerte.glosses.map { it.senseNumber })
            .containsExactly("1", "", "", "2", "").inOrder()

        // Definitions carry their span.orden numbers.
        assertThat(word.definitions.map { it.senseNumber })
            .containsExactly("1", "2", "3").inOrder()

        // .sols sub-entries are parsed as separate headwords, each with its
        // own __ref, not as trailing definitions of the parent lemma.
        assertThat(words[1].mTitle).isEqualTo("muerte natural")
        assertThat(words[1].uri.toString()).endsWith("?__ref=2")
        assertThat(words[1].xrefs).containsExactly("2")
        assertThat(words[1].definitions).hasSize(1)
        assertThat(words[1].definitions[0].glosses[0].definition)
            .isEqualTo("Muerte (→ 1) producida por enfermedad y no por accidente o de forma violenta.")
        assertThat(words[1].definitions[0].glosses[0].examples)
            .containsExactly("El forense certificó que había fallecido de muerte natural.")

        assertThat(words[2].mTitle).isEqualTo("muerte violenta")
        assertThat(words[2].uri.toString()).endsWith("?__ref=3")
        assertThat(words[2].xrefs).containsExactly("3")
        assertThat(words[2].definitions).hasSize(1)
        assertThat(words[2].definitions[0].glosses[0].definition)
            .isEqualTo("Muerte (→ 1) que se produce de forma accidental o violenta.")
        assertThat(words[2].definitions[0].glosses[0].examples)
            .containsExactly("La policía no descarta una muerte violenta a manos de su novio.")

        // Every page word carries the full renderable entry list (page order,
        // itself included) so the renderer can draw all entries in one page.
        for (w in words) {
            assertThat(w.mHomonymEntries).hasSize(3)
            assertThat(w.mHomonymEntries.map { it.mTitle })
                .containsExactly("muerte", "muerte natural", "muerte violenta").inOrder()
            assertThat(w.mHomonymEntries.map { it.ref }).containsExactly("1", "2", "3").inOrder()
        }
        // Each entry snapshot carries that entry's own content.
        assertThat(word.mHomonymEntries[0].definitions).hasSize(3)
        assertThat(word.mHomonymEntries[1].mTitle).isEqualTo("muerte natural")
        assertThat(word.mHomonymEntries[1].definitions).hasSize(1)
        assertThat(word.mHomonymEntries[1].definitions[0].glosses[0].definition)
            .contains("producida por enfermedad")
        assertThat(word.mHomonymEntries[2].mTitle).isEqualTo("muerte violenta")

        assertGolden(words, "../testdata/est/muerte.json")
    }

    @Test
    fun testParseEstOtro() {
        val htmlFile = Goldens.fixture("../testdata/est/otro.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.rae.es/diccionario-estudiante/otro")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("otro, tra")
        assertThat(word.rawHeadword).isEqualTo("otro")
        assertThat(word.definitions).hasSize(6)
        assertThat(word.idioms).hasSize(2)

        // Def 1: Distinto de la persona o cosa mencionadas...
        val def1 = word.definitions[0]
        assertThat(def1.glosses).hasSize(3)
        assertThat(def1.glosses[0].grammar).isEqualTo("adjetivo")
        assertThat(def1.glosses[0].definition).isEqualTo("Distinto de la persona o cosa mencionadas o que puede identificar el oyente.")
        // Second gloss is a usage note, not grammar
        assertThat(def1.glosses[1].definition).contains("Se usa antepuesto al nombre")
        assertThat(def1.glosses[1].grammar).isEqualTo("")
        // Third gloss "Tb. sustantivado" — no grammar
        assertThat(def1.glosses[2].definition).isEqualTo("También sustantivado.")
        assertThat(def1.glosses[2].grammar).isEqualTo("")

        // Def 3: Siguiente — second gloss is a usage note, not "artículo"
        val def3 = word.definitions[2]
        assertThat(def3.glosses[0].grammar).isEqualTo("adjetivo")
        assertThat(def3.glosses[0].definition).isEqualTo("Siguiente.")
        assertThat(def3.glosses).hasSize(2)
        assertThat(def3.glosses[1].definition).isEqualTo("Se usa precedido de artículo")
        assertThat(def3.glosses[1].grammar).isEqualTo("")

        // Def 4: Seguido de un nombre que expresa tiempo — usage note, not "artículo"
        val def4 = word.definitions[3]
        assertThat(def4.glosses).hasSize(2)
        assertThat(def4.glosses[1].definition).contains("Se usa precedido de artículo")
        assertThat(def4.glosses[1].grammar).isEqualTo("")

        assertGolden(words, "../testdata/est/otro.json")
    }

    @Test
    fun testParseEstTrueHomonyms() {
        // A real homonym page: two <article> lemmas sharing the same headword
        // ("cura"), each with its own etymology and definitions.
        val page = """
            <!DOCTYPE html><html><head></head><body>
            <div id="resultados">
            <article>
                <header><span class="entrada">cura</span></header>
                <div class="acep"><abbr class="gram" title="nombre femenino">f.</abbr>
                    <span class="def">Atención o cuidado.</span></div>
            </article>
            <article>
                <header><span class="entrada">cura</span></header>
                <div class="acep"><span class="def">Persona que ejerce el sacerdocio.</span></div>
            </article>
            </div>
            </body></html>
        """.trimIndent()
        val uri = httpUrl("https://www.rae.es/diccionario-estudiante/cura")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(2)
        assertThat(words.map { it.mTitle }).containsExactly("cura", "cura")

        // First homonym keeps the canonical URL; the second resolves via __ref.
        assertThat(words[0].uri.toString()).doesNotContain("__ref")
        assertThat(words[0].xrefs).containsExactly("1")
        assertThat(words[1].uri.toString()).endsWith("?__ref=2")
        assertThat(words[1].xrefs).containsExactly("2")

        // Each entry snapshots the whole set, keyed by the same refs.
        for (word in words) {
            assertThat(word.mHomonymEntries).hasSize(2)
            assertThat(word.mHomonymEntries.map { it.ref }).containsExactly("1", "2").inOrder()
            assertThat(word.mHomonymEntries.map { it.mTitle }).containsExactly("cura", "cura").inOrder()
        }

        // Entry snapshots keep each homonym's own definitions.
        val first = words[0].mHomonymEntries[0]
        assertThat(first.definitions).hasSize(1)
        assertThat(first.definitions[0].glosses[0].grammar).isEqualTo("nombre femenino")
        assertThat(first.definitions[0].glosses[0].definition).isEqualTo("Atención o cuidado.")
        assertThat(first.definitions[0].glosses[0].gender).isEqualTo(Genders.FEMININE)

        val second = words[0].mHomonymEntries[1]
        assertThat(second.definitions).hasSize(1)
        assertThat(second.definitions[0].glosses[0].grammar).isEmpty()
        assertThat(second.definitions[0].glosses[0].definition)
            .isEqualTo("Persona que ejerce el sacerdocio.")
    }

    @Test
    fun testParseSearch() {
        val body = Goldens.fixtureText("../testdata/est-search.json")

        val results = EstParser.parseSearch(body) { item ->
            httpUrl("https://www.rae.es/diccionario-estudiante/$item")
        }

        assertThat(results).hasSize(10)
        assertThat(results.first().mTitle).isEqualTo("frente")
        assertThat(results.first().uri.toString())
            .isEqualTo("https://www.rae.es/diccionario-estudiante/frente")
        assertThat(results.last().mTitle).isEqualTo("en frente")
        assertThat(results.last().uri.toString())
            .isEqualTo("https://www.rae.es/diccionario-estudiante/en%20frente")
    }

    @Test
    fun testParseSearchToleratesNonArrayBodies() {
        val results = EstParser.parseSearch("not json") { item ->
            httpUrl("https://www.rae.es/diccionario-estudiante/$item")
        }

        assertThat(results).isEmpty()
    }

    @Test
    fun testHeadwordAndSynonymSupPreserved() {
        // RAE entry numbers (e.g. tapa1/tapa2) carry the homograph number in
        // a <sup>; it must survive as <sup> HTML so the renderer shows it
        // superscripted instead of a flat "1" suffix.
        val page = """
            <!DOCTYPE html><html><head></head><body>
            <div id="resultados">
            <article>
                <header><span class="entrada">tapa<sup>1</sup></span></header>
                <div class="acep"><abbr class="gram" title="nombre femenino">f.</abbr>
                    <span class="def">Pieza que cierra.</span><div class="refS"><a class="synon" href="cubierta">cubierta<sup>2</sup></a></div></div>
            </article>
            <article>
                <header><span class="entrada">tapa<sup>2</sup></span></header>
                <div class="acep"><abbr class="gram" title="nombre femenino">f.</abbr>
                    <span class="def">Ración de comida.</span></div>
            </article>
            </div>
            </body></html>
        """.trimIndent()
        val uri = httpUrl("https://www.rae.es/diccionario-estudiante/tapa")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(2)
        assertThat(words[0].mTitle).isEqualTo("tapa<sup>1</sup>")
        assertThat(words[1].mTitle).isEqualTo("tapa<sup>2</sup>")
        assertThat(words[0].mSlug).isEqualTo("tapa1")
        assertThat(words[0].rawHeadword).isEqualTo("tapa1")
        assertThat(words[0].definitions[0].synonyms.map { it.text })
            .containsExactly("cubierta<sup>2</sup>")
    }
}