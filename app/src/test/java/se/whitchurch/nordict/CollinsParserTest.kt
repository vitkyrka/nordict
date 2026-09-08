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
class CollinsParserTest {

    data class WordData(
        val mTitle: String,
        val mSlug: String,
        val summary: String,
        val uri: String,
        val dictionary: String = "",
        val xrefs: List<String> = emptyList(),
        val audio: List<String> = emptyList(),
        val definitions: List<DefinitionData> = emptyList(),
        val idioms: List<IdiomData> = emptyList()
    )

    data class DefinitionData(
        val glosses: List<GlossData> = emptyList(),
        val pos: String = "",
        val idioms: List<PhraseData> = emptyList(),
        val phrases: List<PhraseData> = emptyList(),
        val domain: String = "",
        val geo: String = "",
        val plev: String = "",
        val register: String = ""
    )

    data class PhraseData(
        val headword: String,
        val translation: String = "",
        val examples: List<String> = emptyList()
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

    private fun Word.Gloss.toData(): GlossData = GlossData(
        definition = definition,
        headword = headword,
        grammar = grammar,
        gender = gender,
        examples = examples
    )

    private fun Word.Phrase.toData(): PhraseData = PhraseData(
        headword = headword,
        translation = translation,
        examples = examples
    )

    private fun Word.toData(): WordData = WordData(
        mTitle = mTitle,
        mSlug = mSlug,
        summary = summary,
        uri = uri.toString(),
        dictionary = dictionary,
        xrefs = xrefs,
        audio = audio,
        definitions = definitions.map { def ->
            DefinitionData(
                glosses = def.glosses.map { it.toData() },
                pos = def.pos,
                idioms = def.idioms.map { it.toData() },
                phrases = def.phrases.map { it.toData() },
                domain = def.domain,
                geo = def.geo,
                plev = def.plev,
                register = def.register
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
        }
    )

    private fun assertGolden(name: String) {
        val htmlFile = File("../testdata/colspan/$name.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.collinsdictionary.com/dictionary/spanish-english/$name")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/colspan/$name.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/colspan/$name.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }

    @Test
    fun testParseFrente() {
        val htmlFile = File("../testdata/colspan/frente.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.collinsdictionary.com/dictionary/spanish-english/frente")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(3)

        // Main dictionary headword first.
        val main = words[0]
        assertThat(main.mTitle).isEqualTo("frente")
        assertThat(main.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(main.audio).hasSize(2)
        assertThat(main.audio[0]).contains("ES-419")
        assertThat(main.audio[1]).contains("ES-ES")
        assertThat(main.definitions).hasSize(2)

        // First POS group: feminine noun with idioms and phrases.
        val fem = main.definitions[0]
        assertThat(fem.pos).isEqualTo("feminine noun")
        assertThat(fem.glosses).hasSize(1)
        assertThat(fem.idioms).hasSize(4)
        assertThat(fem.phrases).hasSize(1)
        assertThat(fem.idioms[0].headword).isEqualTo("adornar la frente a alguien")

        // Second POS group: masculine noun with 9 phrases.
        val masc = main.definitions[1]
        assertThat(masc.pos).isEqualTo("masculine noun")
        assertThat(masc.glosses).hasSize(6)
        assertThat(masc.idioms).isEmpty()
        assertThat(masc.phrases).hasSize(9)

        // Easy-learning headwords follow (no audio).
        assertThat(words[1].mTitle).isEqualTo("la frente")
        assertThat(words[1].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[1].audio).isEmpty()
        assertThat(words[1].uri.toString()).contains("__ref=1")

        assertThat(words[2].mTitle).isEqualTo("el frente")
        assertThat(words[2].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[2].uri.toString()).contains("__ref=2")

        // Main headword keeps the canonical URL (no __ref).
        assertThat(main.uri.toString()).doesNotContain("__ref")
        assertThat(main.xrefs).containsExactly("3")

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/colspan/frente.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/colspan/frente.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }

    @Test
    fun testParseCagar() {
        val htmlFile = File("../testdata/colspan/cagar.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.collinsdictionary.com/dictionary/spanish-english/cagar")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(1)
        val word = words[0]
        assertThat(word.mTitle).isEqualTo("cagar")
        assertThat(word.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(word.definitions).hasSize(3)

        // intransitive verb: 1 idiom
        assertThat(word.definitions[0].pos).isEqualTo("intransitive verb")
        assertThat(word.definitions[0].idioms).hasSize(1)
        assertThat(word.definitions[0].idioms[0].headword).isEqualTo("¡está que no caga!")

        // transitive verb: 4 glosses, 3 idioms
        assertThat(word.definitions[1].pos).isEqualTo("transitive verb")
        assertThat(word.definitions[1].glosses).hasSize(4)
        assertThat(word.definitions[1].idioms).hasSize(3)

        // reflexive verb: resolves to the zero-sense cagarse cross-ref
        assertThat(word.definitions[2].pos).isEqualTo("reflexive verb")
        assertThat(word.definitions[2].glosses).hasSize(1)

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/colspan/cagar.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/colspan/cagar.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }

    @Test
    fun testParseMorir() {
        val htmlFile = File("../testdata/colspan/morir.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.collinsdictionary.com/dictionary/spanish-english/morir")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(2)

        val main = words[0]
        assertThat(main.mTitle).isEqualTo("morir")
        assertThat(main.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(main.definitions).hasSize(2)
        assertThat(main.definitions[0].pos).isEqualTo("intransitive verb")
        assertThat(main.definitions[0].glosses[0].examples).hasSize(4)
        assertThat(main.definitions[0].idioms).hasSize(2)
        assertThat(main.definitions[0].phrases).hasSize(5)

        // single-sense reflexive cross-ref
        assertThat(main.definitions[1].pos).isEqualTo("reflexive verb")
        assertThat(main.definitions[1].glosses).hasSize(1)

        // Easy-learning entry
        assertThat(words[1].mTitle).isEqualTo("morir")
        assertThat(words[1].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[1].definitions).hasSize(1)
        assertThat(words[1].definitions[0].pos).isEqualTo("verb")

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/colspan/morir.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/colspan/morir.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }

    @Test
    fun testParseMuerte() {
        val htmlFile = File("../testdata/colspan/muerte.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.collinsdictionary.com/dictionary/spanish-english/muerte")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(2)

        val main = words[0]
        assertThat(main.mTitle).isEqualTo("muerte")
        assertThat(main.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(main.definitions).hasSize(1)
        assertThat(main.definitions[0].pos).isEqualTo("feminine noun")
        assertThat(main.definitions[0].glosses).hasSize(3)
        assertThat(main.definitions[0].idioms).hasSize(4)
        assertThat(main.definitions[0].phrases).hasSize(8)

        // Examples of phrase "una lucha a muerte"
        assertThat(main.definitions[0].phrases[0].headword).isEqualTo("una lucha a muerte")
        assertThat(main.definitions[0].phrases[0].examples).hasSize(4)

        val easy = words[1]
        assertThat(easy.mTitle).isEqualTo("la muerte")
        assertThat(easy.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(easy.definitions[0].phrases).hasSize(2)

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/colspan/muerte.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/colspan/muerte.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }

    @Test
    fun testParseOtro() {
        val htmlFile = File("../testdata/colspan/otro.html")
        val page = htmlFile.readText()
        val uri = Uri.parse("https://www.collinsdictionary.com/dictionary/spanish-english/otro")
        val words = CollinsParser.parse(page, uri, "COLSPAN", "spanish-english")

        assertThat(words).hasSize(2)

        val main = words[0]
        assertThat(main.mTitle).isEqualTo("otro")
        assertThat(main.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(main.definitions).hasSize(2)

        // adjective: 3 glosses, 5 phrases
        assertThat(main.definitions[0].pos).isEqualTo("adjective")
        assertThat(main.definitions[0].glosses).hasSize(3)
        assertThat(main.definitions[0].phrases).hasSize(5)

        // pronoun: 4 glosses, 1 idiom, 3 phrases
        assertThat(main.definitions[1].pos).isEqualTo("pronoun")
        assertThat(main.definitions[1].glosses).hasSize(4)
        assertThat(main.definitions[1].idioms).hasSize(1)
        assertThat(main.definitions[1].phrases).hasSize(3)

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/colspan/otro.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/colspan/otro.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }
}
