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
        val xrefs: List<String>
    )

    data class DefinitionData(
        val definition: String,
        val examples: List<String>,
        val grammar: String,
        val domain: String
    )

    data class IdiomData(
        val idiom: String,
        val definition: String,
        val examples: List<String>
    )

    private fun Word.toData(): WordData {
        return WordData(
            mTitle = mTitle,
            mSlug = mSlug,
            summary = summary,
            uri = uri.toString(),
            definitions = definitions.map { def ->
                DefinitionData(
                    definition = def.definition,
                    examples = def.examples,
                    grammar = def.grammar,
                    domain = def.domain
                )
            },
            idioms = idioms.map { idiom ->
                IdiomData(
                    idiom = idiom.idiom,
                    definition = idiom.definition,
                    examples = idiom.examples
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
}
