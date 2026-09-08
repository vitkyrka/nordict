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
        val examples: List<String>,
        val idioms: List<PhraseData> = emptyList(),
        val phrases: List<PhraseData> = emptyList()
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
        examples = examples,
        idioms = idioms.map { it.toData() },
        phrases = phrases.map { it.toData() }
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

        assertThat(words).hasSize(4)

        // Main dictionary headwords first: each POS-group hom is its own Word.
        val fem = words[0]
        assertThat(fem.mTitle).isEqualTo("frente")
        assertThat(fem.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(fem.audio).hasSize(2)
        assertThat(fem.audio[0]).contains("ES-419")
        assertThat(fem.audio[1]).contains("ES-ES")
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
        val masc = words[1]
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

        // Main headwords keep the canonical URL for the first, __ref for the rest.
        assertThat(fem.uri.toString()).doesNotContain("__ref")
        assertThat(fem.xrefs).containsExactly("3")
        assertThat(masc.uri.toString()).contains("__ref=4")
        assertThat(masc.xrefs).containsExactly("4")

        // Easy-learning headwords follow (no audio).
        assertThat(words[2].mTitle).isEqualTo("la frente")
        assertThat(words[2].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[2].audio).isEmpty()
        assertThat(words[2].uri.toString()).contains("__ref=1")

        assertThat(words[3].mTitle).isEqualTo("el frente")
        assertThat(words[3].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[3].uri.toString()).contains("__ref=2")

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

        assertThat(words).hasSize(3)

        val intr = words[0]
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
        val reflex = words[1]
        assertThat(reflex.mTitle).isEqualTo("morir")
        assertThat(reflex.definitions).hasSize(1)
        assertThat(reflex.definitions[0].pos).isEqualTo("reflexive verb")
        assertThat(reflex.definitions[0].glosses).hasSize(1)

        // Easy-learning entry
        assertThat(words[2].mTitle).isEqualTo("morir")
        assertThat(words[2].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[2].definitions).hasSize(1)
        assertThat(words[2].definitions[0].pos).isEqualTo("verb")

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
        assertThat(main.definitions[0].idioms).isEmpty()
        assertThat(main.definitions[0].phrases).isEmpty()
        assertThat(main.definitions[0].glosses[0].idioms).hasSize(4)
        assertThat(main.definitions[0].glosses[0].phrases).hasSize(7)
        assertThat(main.definitions[0].glosses[1].phrases).hasSize(1)

        // Phrases of sense 1 include "una lucha a muerte"
        assertThat(main.definitions[0].glosses[0].phrases[0].headword).isEqualTo("una lucha a muerte")
        assertThat(main.definitions[0].glosses[0].phrases[0].examples).hasSize(4)

        // Easy-learning entry: the two roaming phrases stay at definition level.
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

        assertThat(words).hasSize(3)

        // adjective: 3 glosses; 5 phrases spread across the senses
        val adj = words[0]
        assertThat(adj.mTitle).isEqualTo("otro")
        assertThat(adj.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(adj.definitions).hasSize(1)
        assertThat(adj.definitions[0].pos).isEqualTo("adjective")
        assertThat(adj.definitions[0].glosses).hasSize(3)
        assertThat(adj.definitions[0].phrases).isEmpty()
        assertThat(adj.definitions[0].glosses[0].phrases).hasSize(3)
        assertThat(adj.definitions[0].glosses[1].phrases).hasSize(2)

        // pronoun: 4 glosses; 1 idiom and 3 phrases on the senses they belong to
        val pron = words[1]
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

        // Easy-learning entry: roaming phrases on the definition, two on sense 1
        assertThat(words[2].mTitle).isEqualTo("otro")
        assertThat(words[2].dictionary).isEqualTo("Collins Easy Learning")
        assertThat(words[2].definitions).hasSize(1)
        assertThat(words[2].definitions[0].pos).isEqualTo("adjective or pronoun")
        assertThat(words[2].definitions[0].glosses).hasSize(2)
        assertThat(words[2].definitions[0].phrases).hasSize(6)
        assertThat(words[2].definitions[0].glosses[0].phrases).hasSize(2)

        val gson = GsonBuilder().setPrettyPrinting().create()
        val wordsData = words.map { it.toData() }
        File("../testdata/colspan/otro.json").writeText(gson.toJson(wordsData))

        val expectedJson = File("../testdata/colspan/otro.json").readText()
        val expectedData = gson.fromJson(expectedJson, Array<WordData>::class.java).toList()

        assertThat(wordsData).isEqualTo(expectedData)
    }
}
