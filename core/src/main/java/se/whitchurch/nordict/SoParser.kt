package se.whitchurch.nordict

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class SoParser {
    companion object {
        private const val MP3_TEMPLATE = "https://isolve-so-service.appspot.com/pronounce?id=%s"

        private fun normalizePos(pos: String): Pos = when (pos.trim().lowercase()) {
            "adjektiv" -> Pos.ADJECTIVE
            "adverb" -> Pos.ADVERB
            "konjunktion" -> Pos.CONJUNCTION
            "interjektion" -> Pos.INTERJECTION
            "preposition" -> Pos.PREPOSITION
            "pronomen" -> Pos.PRONOUN
            "substantiv" -> Pos.NOUN
            "verb" -> Pos.VERB
            else -> Pos.UNKNOWN
        }

        private fun obj(e: JsonElement?, key: String): JsonObject? =
            e?.takeIf { it.isJsonObject }?.asJsonObject?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun str(e: JsonElement?, key: String): String? =
            e?.takeIf { it.isJsonObject }?.asJsonObject?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

        private fun arr(e: JsonElement?, key: String): List<JsonElement>? =
            e?.takeIf { it.isJsonObject }?.asJsonObject?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

        private fun strings(e: JsonElement?, key: String): List<String> =
            arr(e, key)
                ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
                ?: emptyList()

        /**
         * `svenska.se/api/autocomplete` responses: a `{"saol","so","saob"}` map of
         * suggestion arrays. Each SO item carries `{label, word_class, target:
         * {id,...}}` where `target.id` is the article `l_nr`; `uriOf` maps it onto
         * the dictionary's article URL. The app's `SoDictionary` and the CLI share
         * this decoder.
         */
        fun parseSearch(body: String, uriOf: (id: String) -> HttpUrl): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            try {
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return results
                val so = root.asJsonObject.get("so")
                if (so == null || !so.isJsonArray) return results
                so.asJsonArray.forEach { el ->
                    if (!el.isJsonObject) return@forEach
                    val obj = el.asJsonObject
                    val label = obj.get("label")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                    val target = obj.get("target")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val id = target.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                    val summary = obj.get("word_class")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    results.add(SearchResult(label, summary, uriOf(id)))
                }
            } catch (_: Exception) {
            }
            return results
        }

        /**
         * Parses an SO article (the `svenska.se/api/article/so/<l_nr>` JSON: a
         * `{"_source": {...}}` object, or a bare `_source`) into one JSON-renderable
         * `Word`. Each SO homograph is its own numbered article, so one page yields
         * exactly one word with no homograph navigation.
         *
         * `ortografi` is the headword, `ordklass` the POS label, `böjning` (HTML)
         * the conjugation, `uttal` entries the pronunciation and pronunciation-clip
         * filenames. Each `huvudbetydelse` sense becomes one `Word.Definition`
         * (`definition_full` gloss, `syntex` examples, `bruklighetskommentar`
         * register, `formkommentar` appended as a trailing parenthetical) whose
         * nested `underbetydelser` become extra glosses with their own `typ` text
         * and `syntex` examples. Idioms with a `hänvisning` cross-reference to
         * another headword (e.g. "stor som ett hus") are dropped; real idioms
         * (`idiombetydelser`) keep their leading `definitionsinledare`, definition,
         * `definitionstillägg`, `exempel` example, and `bruklighetskommentar`.
         */
        fun parse(
            page: String,
            uri: HttpUrl,
            tag: String = "foo",
            baseUrl: String = "https://svenska.se"
        ): List<Word> {
            val words = ArrayList<Word>()
            val root = try {
                JsonParser.parseString(page)
            } catch (_: Exception) {
                return words
            }
            val source = obj(root, "_source") ?: root.takeIf { it.isJsonObject }?.asJsonObject ?: return words

            val headword = str(source, "ortografi")?.takeIf { it.isNotBlank() } ?: return words
            val ordklass = str(source, "ordklass") ?: ""

            val word = Word(
                tag, headword, headword, headword, uri,
                xrefs = arrayListOf(str(source, "l_nr") ?: "")
            )
            word.rawHeadword = headword
            word.pos = normalizePos(ordklass)

            str(source, "böjning")?.let {
                word.conjugation = Jsoup.parse(it).text().trim()
            }

            val pronunciation = ArrayList<String>()
            arr(source, "uttal")?.forEach { u ->
                str(u, "lemmaMedTryckangivelse")?.let { pronunciation.add(it.trim()) }
                str(u, "filnamnInlästUttal")?.let { filnamn ->
                    word.audio.add(MP3_TEMPLATE.format(filnamn.replace(".m4a", ".mp3").replace(" ", "_")))
                }
            }
            word.pronunciation = pronunciation.joinToString(" / ")

            arr(source, "huvudbetydelser")?.forEachIndexed { index, bite ->
                // The site numbers senses by position (1, 2, 3, …), so the
                // array index is the original numbering.
                val definition = parseBite(bite, ordklass, (index + 1).toString())
                if (definition != null) word.definitions.add(definition)

                arr(bite, "idiom")?.forEach { idiomObj ->
                    parseIdiom(idiomObj, ordklass)?.let { word.idioms.add(it) }
                }
            }

            words.add(word)
            return words
        }

        /** One primary sense plus its nested sub-senses. */
        private fun parseBite(bite: JsonElement?, ordklass: String, senseNumber: String): Word.Definition? {
            val glosses = ArrayList<Word.Gloss>()

            val formkommentar = str(obj(bite, "formkommentar"), "text")
            val full = str(bite, "definition_full") ?: str(bite, "definition")
                ?: return null
            val primary = Word.Gloss()
            primary.definition = if (formkommentar.isNullOrBlank()) full else "$full ($formkommentar)"
            primary.examples.addAll(strings(bite, "syntex"))
            glosses.add(primary)

            arr(bite, "underbetydelser")?.forEach { sub ->
                val typ = str(sub, "typ") ?: return@forEach
                val subForm = str(obj(sub, "formkommentar"), "text")
                val gloss = Word.Gloss()
                gloss.definition = if (subForm.isNullOrBlank()) typ else "$typ ($subForm)"
                gloss.examples.addAll(strings(sub, "syntex"))
                glosses.add(gloss)
            }

            val definition = Word.Definition(primary.definition, definitionElement(glosses))
            definition.pos = ordklass
            definition.grammar = ordklass
            definition.senseNumber = senseNumber
            definition.register = str(bite, "bruklighetskommentar") ?: ""
            definition.glosses.addAll(glosses)
            definition.examples.addAll(primary.examples)
            return definition
        }

        /** A plain HTML fragment per definition so Anki card backs are not empty. */
        private fun definitionElement(glosses: List<Word.Gloss>): Element {
            val body = Jsoup.parseBodyFragment("").body()
            body.appendElement("div").addClass("gloss")
                .appendElement("span").addClass("definition").text(glosses[0].definition)
            glosses[0].examples.forEach {
                body.appendElement("div").addClass("example").text(it)
            }
            for (i in 1 until glosses.size) {
                body.appendElement("div").addClass("gloss")
                    .appendElement("span").addClass("definition").text(glosses[i].definition)
                glosses[i].examples.forEach {
                    body.appendElement("div").addClass("example").text(it)
                }
            }
            return body
        }

        /** One fixed expression with its senses; cross-reference-only idioms return null. */
        private fun parseIdiom(idiomObj: JsonElement?, ordklass: String): Word.Idiom? {
            val phrase = str(idiomObj, "idiom")?.takeIf { it.isNotBlank() } ?: return null
            val senses = arr(idiomObj, "idiombetydelser") ?: return null
            if (senses.isEmpty()) return null

            val glosses = ArrayList<Word.Gloss>()
            var register = ""
            var first = ""
            senses.forEach { sense ->
                val parts = listOfNotNull(
                    str(sense, "definitionsinledare"),
                    str(sense, "definition"),
                    str(sense, "definitionstillägg")
                )
                val defText = parts.joinToString(" ").trim()
                if (defText.isEmpty()) return@forEach
                if (first.isEmpty()) first = defText
                val gloss = Word.Gloss()
                gloss.definition = defText
                str(sense, "exempel")?.let { gloss.examples.add(it.trim()) }
                glosses.add(gloss)
                if (register.isEmpty()) register = str(sense, "bruklighetskommentar") ?: ""
            }
            if (glosses.isEmpty()) return null

            val idiom = Word.Idiom(phrase, first)
            idiom.register = register
            idiom.glosses.addAll(glosses)
            idiom.examples.addAll(glosses.flatMap { it.examples })
            return idiom
        }
    }
}