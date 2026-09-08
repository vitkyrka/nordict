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
class EstParserTest {

    data class WordData(
        val mTitle: String,
        val mSlug: String,
        val summary: String,
        val uri: String,
        val definitions: List<DefinitionData>,
        val idioms: List<IdiomData>,
        val xrefs: List<String>,
        val conjugation: String = "",
        val participle: String = ""
    )

    data class DefinitionData(
        val glosses: List<GlossData>,
        val domain: String = "",
        val geo: String = "",
        val plev: String = "",
        val register: String = "",
        val synonyms: List<SynonymData> = emptyList()
    )

    data class SynonymData(
        val text: String,
        val href: String,
        val plev: String
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
            definitions = definitions.map { def ->
                DefinitionData(
                    glosses = def.glosses.map { it.toData() },
                    domain = def.domain,
                    geo = def.geo,
                    plev = def.plev,
                    register = def.register,
                    synonyms = def.synonyms.map { SynonymData(it.text, it.href, it.plev) }
                )
            },
            idioms = idioms.map { idiom ->
                IdiomData(
                    idiom = idiom.idiom,
                    glosses = idiom.glosses.map { it.toData() },
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
    fun testParseEst() {
        val htmlFile = File("../testdata/est.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.rae.es/diccionario-estudiante/frente")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("frente")
        assertThat(word.definitions).hasSize(6)
        assertThat(word.idioms).hasSize(14)

        assertGolden(words, "../testdata/est.json")
    }

    @Test
    fun testParseEstCagar() {
        val htmlFile = File("../testdata/est/cagar.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.rae.es/diccionario-estudiante/cagar")
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

        assertGolden(words, "../testdata/est/cagar.json")
    }

    @Test
    fun testParseEstMorir() {
        val htmlFile = File("../testdata/est/morir.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.rae.es/diccionario-estudiante/morir")
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
        assertThat(def1.synonyms.map { SynonymData(it.text, it.href, it.plev) }).containsExactly(
            SynonymData("expirar", "https://www.rae.es/diccionario-estudiante/expirar", "")
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
        val htmlFile = File("../testdata/est/muerte.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.rae.es/diccionario-estudiante/muerte")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(3)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("muerte")
        // 3 main definitions; the .sols sub-entries are separate headwords
        assertThat(word.definitions).hasSize(3)
        assertThat(word.idioms).hasSize(7)
        assertThat(word.conjugation).isEmpty()
        assertThat(word.participle).isEmpty()
        assertThat(word.xrefs).containsExactly("1")

        // Definition 1: synonym from a relative <a class="synon" href="...">
        val def1 = word.definitions[0]
        assertThat(def1.glosses[0].definition).isEqualTo(
            "Término de la vida de una persona o de otro ser vivo."
        )
        assertThat(def1.synonyms.map { SynonymData(it.text, it.href, it.plev) }).containsExactly(
            SynonymData("defunción", "https://www.rae.es/diccionario-estudiante/defunción", "")
        )

        // Regression: every idiom must be a real acep, not the <a class="acep">
        // cross-reference anchors in the definition text (which produced ghost,
        // zero-gloss duplicates). "la Muerte" must appear exactly once.
        assertThat(word.idioms.map { it.glosses.size }).containsExactly(1, 3, 2, 1, 1, 2, 1)
        assertThat(word.idioms.map { it.idiom }).containsExactly(
            "a la muerte",
            "a muerte",
            "a muerte",
            "dar muerte (a alguien)",
            "de mala muerte",
            "de muerte",
            "la Muerte"
        ).inOrder()

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

        assertGolden(words, "../testdata/est/muerte.json")
    }

    @Test
    fun testParseEstOtro() {
        val htmlFile = File("../testdata/est/otro.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.rae.es/diccionario-estudiante/otro")
        val words = EstParser.parse(page, uri, "EST")

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("otro, tra")
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
}
