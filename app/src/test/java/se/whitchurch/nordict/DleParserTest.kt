package se.whitchurch.nordict

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DleParserTest {

    data class WordData(
        val title: String,
        val slug: String,
        val summary: String,
        val uri: String,
        val definitions: List<DefinitionData>,
        val idioms: List<IdiomData>,
        val xrefs: List<String>
    )

    data class DefinitionData(
        val definition: String,
        val examples: List<String>
    )

    data class IdiomData(
        val idiom: String,
        val definition: String,
        val examples: List<String>
    )

    private fun Word.toData(): WordData {
        return WordData(
            title = mTitle,
            slug = mSlug,
            summary = summary,
            uri = uri.toString(),
            definitions = definitions.map { def ->
                DefinitionData(
                    definition = def.definition,
                    examples = def.examples
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
    fun testParseDle() {
        val htmlFile = File("../testdata/dle.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://dle.rae.es/frente")
        val words = DleParser.parse(page, uri, "")

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        val json = gson.toJson(wordsData)

        // For initial creation:
        // File("../testdata/dle.json").writeText(json)

        val expectedJson = File("../testdata/dle.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }
}
