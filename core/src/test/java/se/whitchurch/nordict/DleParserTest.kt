package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the DLE parser. Shares the exact `WordJson` mapping
 * and goldens the CLI emits, so the app, the desktop CLI, and the test suite
 * all agree on the same JSON.
 */
class DleParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseDleFrente() {
        val htmlFile = File("../testdata/dle/frente.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://dle.rae.es/frente")
        val words = DleParser.parse(page, uri, "DLE")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("frente")
        assertThat(word.definitions).hasSize(14)
        // One Idiom per h3 header (the original groups N numbered li senses
        // under one headword): 19 headers with senses on the frente page
        // ("en frente" heads only a "V. enfrente." cross-reference).
        assertThat(word.idioms).hasSize(19)
        assertThat(word.etymology).contains("Del antiguo fruente")

        val def1 = word.definitions[0]
        assertThat(def1.grammar).isEqualTo("nombre femenino")
        assertThat(def1.gender).isEqualTo(Genders.FEMININE)
        assertThat(def1.glosses[0].definition).contains("Parte superior de la cara")
        assertThat(def1.synonyms.map { it.text }).containsExactly("testa", "testuz")
        assertThat(def1.synonyms[0].href).isEqualTo("https://dle.rae.es/?id=ZecEwPE")

        val def2 = word.definitions[1]
        assertThat(def2.glosses[0].examples).containsExactly("Frente serena.")

        val def6 = word.definitions[5]
        assertThat(def6.domain).isEqualTo("Meteorología")

        val def8 = word.definitions[7]
        assertThat(def8.antonyms).containsExactly("retaguardia")
        assertThat(def8.glosses[0].examples).containsExactly("El escuadrón tenía diez hombres de frente.")

        // First idiom
        val idiom1 = word.idioms[0]
        assertThat(idiom1.idiom).isEqualTo("frente calzada")
        assertThat(idiom1.glosses[0].grammar).isEqualTo("nombre femenino")

        val idiomBatalla = word.idioms.find { it.idiom.contains("frente de batalla") }
        assertThat(idiomBatalla).isNotNull()
        assertThat(idiomBatalla?.domain).isEqualTo("Milicia")

        val lastIdiom = word.idioms.last()
        assertThat(lastIdiom.idiom).isEqualTo("traerlo alguien escrito en la frente")
        assertThat(lastIdiom.glosses[0].definition).contains("No acertar a disimular")

        // Grouped senses keep their per-li .n_acep numbers on each gloss.
        val alFrente = word.idioms.find { it.idiom == "al frente" }
        assertThat(alFrente).isNotNull()
        assertThat(alFrente!!.glosses).hasSize(2)
        assertThat(alFrente.glosses.map { it.senseNumber })
            .containsExactly("1", "2").inOrder()
        val deFrente = word.idioms.find { it.idiom == "de frente" }
        assertThat(deFrente).isNotNull()
        assertThat(deFrente!!.glosses).hasSize(3)
        assertThat(deFrente.glosses.map { it.senseNumber })
            .containsExactly("1", "2", "3").inOrder()

        // Definitions carry their .n_acep numbers.
        assertThat(word.definitions.map { it.senseNumber })
            .containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                "11", "12", "13", "14"
            ).inOrder()

        assertGolden(words, "../testdata/dle/frente.json")
    }

    @Test
    fun testParseDleCagar() {
        val htmlFile = File("../testdata/dle/cagar.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://dle.rae.es/cagar")
        val words = DleParser.parse(page, uri, "DLE")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("cagar")
        assertThat(word.definitions).hasSize(3)
        assertThat(word.idioms).hasSize(4)
        assertThat(word.etymology).contains("cacāre")

        val def1 = word.definitions[0]
        assertThat(def1.grammar).isEqualTo("verbo intransitivo")
        assertThat(def1.register).isEqualTo("malsonante")
        assertThat(def1.glosses[0].definition).contains("Evacuar el vientre")
        assertThat(def1.synonyms.map { it.text }).containsExactly("defecar", "evacuar", "deponer", "excretar")

        val def2 = word.definitions[1]
        assertThat(def2.register).isEqualTo("malsonante coloquial")
        assertThat(def2.grammar).isEqualTo("verbo transitivo")
        assertThat(def2.synonyms.map { it.text }).containsExactly("estropear", "arruinar")

        val idiom1 = word.idioms[0]
        assertThat(idiom1.idiom).isEqualTo("cagarla")

        assertThat(word.definitions.map { it.senseNumber })
            .containsExactly("1", "2", "3").inOrder()

        assertGolden(words, "../testdata/dle/cagar.json")
    }

    @Test
    fun testParseDleMorir() {
        val htmlFile = File("../testdata/dle/morir.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://dle.rae.es/morir")
        val words = DleParser.parse(page, uri, "DLE")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("morir")
        assertThat(word.definitions).hasSize(8)
        assertThat(word.idioms).hasSize(3)
        assertThat(word.conjugation).isEqualTo("dormir")
        assertThat(word.participle).isEqualTo("muerto")
        assertThat(word.etymology).contains("morī")

        val def1 = word.definitions[0]
        assertThat(def1.grammar).isEqualTo("verbo intransitivo")
        assertThat(def1.glosses[0].definition).contains("Llegar al término de la vida")
        assertThat(def1.synonyms.map { it.text }).contains("fallecer")
        assertThat(def1.synonyms.map { it.text }).contains("apagarse")

        // Verify structured synonym fields: malsonante marker on descoñetar
        val descoSyn = def1.synonyms.find { it.text == "descoñetar" }
        assertThat(descoSyn).isNotNull()
        assertThat(descoSyn!!.href).isEqualTo("https://dle.rae.es/?id=CjYRP23")
        assertThat(descoSyn.plev).isEqualTo("malsonante")

        // Verify link-target href on piantarse
        val piantaSyn = def1.synonyms.find { it.text == "piantarse" }
        assertThat(piantaSyn).isNotNull()
        assertThat(piantaSyn!!.href).isEqualTo("https://dle.rae.es/?id=SrurElO")

        assertThat(word.definitions.map { it.senseNumber })
            .containsExactly("1", "2", "3", "4", "5", "6", "7", "8").inOrder()

        assertGolden(words, "../testdata/dle/morir.json")
    }

    @Test
    fun testParseSearch() {
        val body = File("../testdata/dle-search.json").readText()

        val results = DleParser.parseSearch(body) { item ->
            httpUrl("https://dle.rae.es/$item")
        }

        assertThat(results.map { it.mTitle }).containsExactly("frente", "frentero").inOrder()
        assertThat(results[0].uri.toString()).isEqualTo("https://dle.rae.es/frente")
        assertThat(results[1].uri.toString()).isEqualTo("https://dle.rae.es/frentero")
    }

    @Test
    fun testParseSearchStripsDleKeyTags() {
        val body = """["ballet|ballet<span class=\"z\">lit.</span>"]"""

        val results = DleParser.parseSearch(body) { item ->
            httpUrl("https://dle.rae.es/$item")
        }

        assertThat(results.single().mTitle).isEqualTo("ballet")
    }

    @Test
    fun testParseSearchToleratesNonArrayBodies() {
        val results = DleParser.parseSearch("{}") { item ->
            httpUrl("https://dle.rae.es/$item")
        }

        assertThat(results).isEmpty()
    }

    @Test
    fun testParseDleOtro() {
        val htmlFile = File("../testdata/dle/otro.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://dle.rae.es/otro")
        val words = DleParser.parse(page, uri, "DLE")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("otro, tra")
        assertThat(word.rawHeadword).isEqualTo("otro")
        assertThat(word.definitions).hasSize(7)
        // Grouped by h3 header: 7 li senses under 4 headwords.
        assertThat(word.idioms).hasSize(4)
        assertThat(word.etymology).contains("alter")

        val def1 = word.definitions[0]
        assertThat(def1.grammar).isEqualTo("adjetivo")
        assertThat(def1.glosses[0].definition).contains("Dicho de una persona o de una cosa")
        assertThat(def1.synonyms.map { it.text }).containsExactly("diferente", "distinto<sup>1</sup>")
        assertThat(def1.antonyms).containsExactly("mismo")

        assertGolden(words, "../testdata/dle/otro.json")
    }

    @Test
    fun testHeadwordAndSynonymSupPreserved() {
        // RAE entry numbers (e.g. tapa1/tapa2, cara1) carry the homograph
        // number in a <sup>; it must survive as <sup> HTML so the renderer
        // shows it superscripted instead of a flat "1" suffix.
        val page = """
            <!DOCTYPE html><html><head></head><body>
            <div id="resultados">
            <article>
                <header><h1>tapa<sup>1</sup></h1></header>
                <ol class="c-definitions"><li class="j"><div class="c-definitions__item"><div><span class="n_acep">1. </span><abbr class="d" title="nombre femenino">f.</abbr> Pieza que cierra.</div><div class="c-definitions__item-footer"><div class="c-word-list"><div class="c-word-list__label">Sin.:</div><ul class="c-word-list__items"><li><span><span class="sin" data-id="X">cara<sup>1</sup></span></span></li></ul></div></div></div></li></ol>
            </article>
            <article>
                <header><h1>tapa<sup>2</sup></h1></header>
                <ol class="c-definitions"><li class="j"><div class="c-definitions__item"><div><span class="n_acep">1. </span><abbr class="d" title="nombre femenino">f.</abbr> Ración de comida.</div></div></div></li></ol>
            </article>
            </div>
            </body></html>
        """.trimIndent()
        val uri = httpUrl("https://dle.rae.es/tapa")
        val words = DleParser.parse(page, uri, "DLE")

        assertThat(words).hasSize(2)
        assertThat(words[0].mTitle).isEqualTo("tapa<sup>1</sup>")
        assertThat(words[1].mTitle).isEqualTo("tapa<sup>2</sup>")
        // Slug/summary/search key stay plain for lookup and display fallback.
        assertThat(words[0].mSlug).isEqualTo("tapa1")
        assertThat(words[0].rawHeadword).isEqualTo("tapa1")
        assertThat(words[0].definitions[0].synonyms.map { it.text })
            .containsExactly("cara<sup>1</sup>")
    }
}