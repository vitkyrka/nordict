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
         * One `böjningstabell` row: the inflected form (`böjningsform`) for a
         * `postskript` slot ("obestämd form", "bestämd form", …) under a
         * `rubrik` ("Singular"/"Plural"). The singular indefinite slot also
         * carries the indefinite article as `ledtext` ("en"/"ett") — the
         * reliable neuter/common signal that replaces the old site's
         * definite-ending heuristic.
         */
        private fun tableForms(
            source: JsonObject,
            rubrik: String,
            postskript: String
        ): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            arr(source, "böjningstabell")?.forEach { table ->
                if (!table.isJsonObject) return@forEach
                if (str(table, "rubrik") != rubrik) return@forEach
                arr(table, "rader")?.forEach { row ->
                    if (str(row, "postskript") != postskript) return@forEach
                    arr(row, "böjningsvarianter")?.forEach { variant ->
                        val form = str(variant, "böjningsform") ?: return@forEach
                        out.add(Pair(str(variant, "ledtext") ?: "", form))
                    }
                }
            }
            return out
        }

        /**
         * Neuter ("t") vs common ("n") gender for a noun, mirroring the old
         * HTML pipeline's `parseGrammar` values. Preferred signal is the
         * `böjningstabell` singular indefinite `ledtext` ("ett" vs "en");
         * falls back to the old definite-ending heuristic on the `böjning`
         * text (definite singular ending in "t", "neutr." mentions) when the
         * table is absent. Non-nouns return "".
         */
        private fun inflectionGender(source: JsonObject, ordklass: String, bojningText: String): String {
            if (normalizePos(ordklass) != Pos.NOUN) return ""
            tableForms(source, "Singular", "obestämd form").firstOrNull()?.let { (ledtext, _) ->
                when (ledtext.trim().lowercase()) {
                    "ett" -> return "t"
                    "en" -> return "n"
                }
            }
            if (bojningText.contains("ingen böjning", ignoreCase = true)) {
                val neutr = bojningText.indexOf("neutr.")
                if (neutr < 0) return ""
                val ngenus = bojningText.indexOf("n-genus")
                return if (ngenus < 0 || neutr < ngenus) "t" else "n"
            }
            val definite = bojningText.split("[ ,]".toRegex()).firstOrNull { it.isNotEmpty() } ?: return ""
            return if (definite.endsWith("t")) "t" else "n"
        }

        /**
         * Highlights the Kjellin-technique declined form inside the plain-text
         * `böjning`: the definite singular for neuter nouns, the indefinite
         * plural for common nouns (the old pipeline wrapped the same forms in
         * `<strong>`). Forms come from `böjningstabell` when present
         * (singular "bestämd form" / plural "obestämd form"); "~" shorthand
         * is expanded to the headword first. Returns HTML for the renderer's
         * `conjugation` slot, which is inserted unescaped.
         */
        private fun highlightConjugation(
            source: JsonObject,
            headword: String,
            gender: String,
            bojningText: String
        ): String {
            var text = bojningText.replace("~", headword)
            if (gender != "t" && gender != "n") return text
            val definite = tableForms(source, "Singular", "bestämd form")
                .firstOrNull()?.second
                ?: text.split("[ ,]".toRegex()).firstOrNull { it.isNotEmpty() }
                ?: return text
            val plural = tableForms(source, "Plural", "obestämd form")
                .firstOrNull()?.second ?: ""
            val target = if (gender == "t") definite else plural.ifEmpty { return text }
            if (target.isEmpty() || target == headword) return text
            // Whole-token match only, so "huset" doesn't bold inside "husets".
            val regex = Regex("(?<![\\p{L}])${Regex.escape(target)}(?![\\p{L}])")
            return regex.replaceFirst(text, "<strong>$target</strong>")
        }

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
         *
         * Nouns also get the gender memorization aids of the old HTML pipeline:
         * `gender` ("t" neuter / "n" common, from the `böjningstabell`
         * indefinite article) drives the renderer's "(ett)" headword prefix,
         * and the Kjellin-technique declined form (definite singular for
         * neuter, indefinite plural for common) is wrapped in `<strong>` in
         * the `conjugation` HTML.
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

            val bojningText = str(source, "böjning")?.let { Jsoup.parse(it).text().trim() } ?: ""
            word.gender = inflectionGender(source, ordklass, bojningText)
            if (bojningText.isNotEmpty()) {
                word.conjugation = highlightConjugation(source, headword, word.gender, bojningText)
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

        /** A plain HTML fragment per definition (the Definition model keeps its source element). */
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