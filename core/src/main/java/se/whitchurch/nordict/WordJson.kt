package se.whitchurch.nordict

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * The JSON schema the Dart-injected WebView renderer and the desktop CLI consume.
 *
 * These data classes mirror the shape of the committed golden fixtures in
 * `testdata/dle/` (the `.json` files there; also produced by the app's
 * `gson.toJson(word)` path, minus the transient/UI fields). Field order is
 * significant: Gson serializes in declaration order, and the golden fixtures
 * are byte-for-byte comparisons.
 *
 * Renaming or reordering a field here requires updating `testdata/dle/`, the
 * `renderer.js`, and `renderer.test.js` in sync (see AGENTS.md).
 */
object WordJson {
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

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

    fun Word.Gloss.toData(): GlossData {
        return GlossData(
            definition = definition,
            headword = headword,
            grammar = grammar,
            gender = gender,
            examples = examples
        )
    }

    fun toJson(words: List<Word>): String = gson.toJson(words.map { it.toWordData() })
}

/** Maps a parsed [Word] onto the shared golden JSON schema. */
fun Word.toWordData(): WordJson.WordData {
    return WordJson.run {
        WordJson.WordData(
            mTitle = mTitle,
            mSlug = mSlug,
            summary = summary,
            uri = uri.toString(),
            conjugation = conjugation,
            participle = participle,
            etymology = etymology,
            rawHeadword = rawHeadword,
            definitions = definitions.map { def ->
                WordJson.DefinitionData(
                    glosses = def.glosses.map { it.toData() },
                    domain = def.domain,
                    geo = def.geo,
                    plev = def.plev,
                    register = def.register,
                    synonyms = def.synonyms.map { WordJson.SynonymData(it.text, it.href, it.plev) },
                    antonyms = def.antonyms
                )
            },
            idioms = idioms.map { idiom ->
                WordJson.IdiomData(
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
}