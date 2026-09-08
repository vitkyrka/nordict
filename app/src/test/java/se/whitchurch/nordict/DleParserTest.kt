package se.whitchurch.nordict

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DleParserTest {

    data class WordData(
        val mTitle: String,
        val mSlug: String,
        val summary: String,
        val uri: String,
        val definitions: List<DefinitionData>,
        val idioms: List<IdiomData>,
        val xrefs: List<String>,
        val conjugation: String = "",
        val participle: String = "",
        val etymology: String = "",
        val rawHeadword: String = ""
    )

    data class DefinitionData(
        val glosses: List<GlossData>,
        val domain: String = "",
        val geo: String = "",
        val plev: String = "",
        val register: String = "",
        val synonyms: List<SynonymData> = emptyList(),
        val antonyms: List<String> = emptyList()
    )

    data class SynonymData(
        val text: String,
        val href: String = "",
        val plev: String = ""
    )

    data class GlossData(
        val definition: String,
        val headword: String,
        val grammar: String,
        val gender: String,
        val examples: List<String>
    )

    data class IdiomData(
        val idiom: String,
        val glosses: List<GlossData>,
        val domain: String = "",
        val geo: String = "",
        val plev: String = "",
        val register: String = ""
    )

    private fun Word.Gloss.toData(): GlossData {
        return GlossData(
            definition = definition,
            headword = headword,
            grammar = grammar,
            gender = gender,
            examples = examples
        )
    }

    private fun Word.toData(): WordData {
        return WordData(
            mTitle = mTitle,
            mSlug = mSlug,
            summary = summary,
            uri = uri.toString(),
            conjugation = conjugation,
            participle = participle,
            etymology = etymology,
            rawHeadword = rawHeadword,
            definitions = definitions.map { def ->
                DefinitionData(
                    glosses = def.glosses.map { it.toData() },
                    domain = def.domain,
                    geo = def.geo,
                    plev = def.plev,
                    register = def.register,
                    synonyms = def.synonyms.map { SynonymData(it.text, it.href, it.plev) },
                    antonyms = def.antonyms
                )
            },
            idioms = idioms.map { idiom ->
                IdiomData(
                    idiom = idiom.idiom,
                    glosses = idiom.glosses.map { it.toData() },
                    domain = idiom.domain,
                    geo = idiom.geo,
                    plev = idiom.plev,
                    register = idiom.register
                )
            },
            xrefs = xrefs
        )
    }

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toData() }, jsonPath, Array<WordData>::class.java)
    }

    @Test
    fun testParseDleFrente() {
        val htmlFile = File("../testdata/dle/frente.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://dle.rae.es/frente")
        val words = DleParser.parse(page, uri, "DLE")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("frente")
        assertThat(word.definitions).hasSize(14)
        assertThat(word.idioms).hasSize(25)
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

        assertGolden(words, "../testdata/dle/frente.json")
    }

    @Test
    fun testParseDleCagar() {
        val htmlFile = File("../testdata/dle/cagar.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://dle.rae.es/cagar")
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

        assertGolden(words, "../testdata/dle/cagar.json")
    }

    @Test
    fun testParseDleMorir() {
        val htmlFile = File("../testdata/dle/morir.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://dle.rae.es/morir")
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

        assertGolden(words, "../testdata/dle/morir.json")
    }

    @Test
    fun testParseDleOtro() {
        val htmlFile = File("../testdata/dle/otro.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://dle.rae.es/otro")
        val words = DleParser.parse(page, uri, "DLE")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("otro, tra")
        assertThat(word.rawHeadword).isEqualTo("otro")
        assertThat(word.definitions).hasSize(7)
        assertThat(word.idioms).hasSize(7)
        assertThat(word.etymology).contains("alter")

        val def1 = word.definitions[0]
        assertThat(def1.grammar).isEqualTo("adjetivo")
        assertThat(def1.glosses[0].definition).contains("Dicho de una persona o de una cosa")
        assertThat(def1.synonyms.map { it.text }).containsExactly("diferente", "distinto1")
        assertThat(def1.antonyms).containsExactly("mismo")

        assertGolden(words, "../testdata/dle/otro.json")
    }
}
