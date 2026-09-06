package se.whitchurch.nordict

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
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
        val definition: String,
        val examples: List<String>,
        val grammar: String,
        val domain: String,
        val geo: String,
        val gender: String,
        val plev: String,
        val register: String = "",
        val synonyms: List<String> = emptyList(),
        val note: String = ""
    )

    data class IdiomData(
        val idiom: String,
        val definition: String,
        val examples: List<String>,
        val grammar: String,
        val geo: String,
        val gender: String,
        val plev: String,
        val register: String = "",
        val note: String = ""
    )

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
                    definition = def.definition,
                    examples = def.examples,
                    grammar = def.grammar,
                    domain = def.domain,
                    geo = def.geo,
                    gender = def.gender,
                    plev = def.plev,
                    register = def.register,
                    synonyms = def.synonyms,
                    note = def.note
                )
            },
            idioms = idioms.map { idiom ->
                IdiomData(
                    idiom = idiom.idiom,
                    definition = idiom.definition,
                    examples = idiom.examples,
                    grammar = idiom.grammar,
                    geo = idiom.geo,
                    gender = idiom.gender,
                    plev = idiom.plev,
                    register = idiom.register,
                    note = idiom.note
                )
            },
            xrefs = xrefs
        )
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

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/est.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/est.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
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

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/est/cagar.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/est/cagar.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
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

        // Definition 1: Dejar de vivir.
        assertThat(word.definitions[0].grammar).isEqualTo("verbo intransitivo")
        assertThat(word.definitions[0].definition).isEqualTo("Dejar de vivir.")
        assertThat(word.definitions[0].note).isEqualTo("También prnl.")
        assertThat(word.definitions[0].examples).containsExactly(
            "Ha muerto en un accidente.",
            "Se ha muerto de un ataque al corazón."
        )
        assertThat(word.definitions[0].synonyms).containsExactly("expirar")

        // Definition 2: Llegar algo a su fin.
        assertThat(word.definitions[1].grammar).isEqualTo("verbo intransitivo")
        assertThat(word.definitions[1].definition).contains("Llegar")
        assertThat(word.definitions[1].definition).contains("a su fin.")
        assertThat(word.definitions[1].note).isEqualTo("También prnl.")
        assertThat(word.definitions[1].examples).containsExactly(
            "El río muere en esta laguna.",
            "El fuego está a punto de morirse."
        )

        // Definition 3: Sentir intensamente algo. (coloquial)
        assertThat(word.definitions[2].grammar).isEqualTo("verbo intransitivo pronominal")
        assertThat(word.definitions[2].register).isEqualTo("coloquial")
        assertThat(word.definitions[2].definition).isEqualTo("Sentir intensamente algo.")
        assertThat(word.definitions[2].examples).containsExactly(
            "Se muere de ganas de verte.",
            "Estoy muerto de hambre."
        )

        // Definition 4: Reírse mucho. (coloquial)
        assertThat(word.definitions[3].grammar).isEqualTo("verbo intransitivo pronominal")
        assertThat(word.definitions[3].register).isEqualTo("coloquial")
        assertThat(word.definitions[3].definition).isEqualTo("Reírse mucho.")
        assertThat(word.definitions[3].note).isEqualTo("Frecuentemente morirse de risa.")
        assertThat(word.definitions[3].examples).containsExactly(
            "Cuenta unas historias para morirse de risa."
        )

        // Definition 5: Amar intensamente a alguien. (coloquial)
        assertThat(word.definitions[4].grammar).isEqualTo("verbo intransitivo pronominal")
        assertThat(word.definitions[4].register).isEqualTo("coloquial")
        assertThat(word.definitions[4].definition).isEqualTo("Amar intensamente a alguien.")
        assertThat(word.definitions[4].examples).containsExactly(
            "Le dijo que se moría por ella."
        )

        // Definition 6: Desear vehementemente algo. (coloquial)
        assertThat(word.definitions[5].grammar).isEqualTo("verbo intransitivo pronominal")
        assertThat(word.definitions[5].register).isEqualTo("coloquial")
        assertThat(word.definitions[5].definition).isEqualTo("Desear vehementemente algo.")
        assertThat(word.definitions[5].examples).containsExactly(
            "Se muere por conocerlo."
        )

        // Idiom: muera
        assertThat(word.idioms[0].idiom).isEqualTo("muera")
        assertThat(word.idioms[0].grammar).isEqualTo("expresión")
        assertThat(word.idioms[0].definition).contains("expresar rechazo u odio")
        assertThat(word.idioms[0].note).contains("especialmente")
        assertThat(word.idioms[0].examples).isNotEmpty()

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/est/morir.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/est/morir.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }
}
