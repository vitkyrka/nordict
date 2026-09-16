package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class LeRobertParser {
    companion object {

        /**
         * LeRobert `/autocomplete.json` search responses: a JSON array of
         * `{display, page}` objects where `display` carries HTML markup and
         * `page` is a relative path like `/definition/table`. The conjugation
         * view (`/conjugaison/...`) is rewritten to the definition view.
         */
        fun parseSearch(body: String, uriOf: (page: String) -> HttpUrl): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            try {
                val array = JsonParser.parseString(body)
                if (!array.isJsonArray) return results
                array.asJsonArray.forEach { element ->
                    if (!element.isJsonObject) return@forEach
                    val item = element.asJsonObject
                    val display = item["display"]?.takeIf { it.isJsonPrimitive }?.asString
                        ?: return@forEach
                    val page = item["page"]?.takeIf { it.isJsonPrimitive }?.asString
                        ?: return@forEach
                    val title = Jsoup.parse(display).text()
                    if (title.isEmpty()) return@forEach
                    val rewritten = page.replace("/conjugaison/", "/definition/")
                    results.add(SearchResult(title, uriOf(rewritten)))
                }
            } catch (_: Exception) {
            }
            return results
        }

        fun parse(
            page: String,
            uri: HttpUrl,
            tag: String,
            baseUrl: String = "https://dictionnaire.lerobert.com/"
        ): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val doc = Jsoup.parse(page, finalBaseUrl)

            val main = doc.selectFirst("div.ws-c") ?: return emptyList()
            val word = main.selectFirst("h1")?.text() ?: return emptyList()

            val cleanpage = doc.head().html() + "<body>" + main
            main.selectFirst("div.ws-a")?.remove()

            // Pre-parse synonyms grouped by POS label.
            val synByPos = parseSynonyms(main, finalBaseUrl)

            var first = true
            var ref = 0

            main.selectFirst("section.def")?.select("div.b")?.forEach { lemma ->
                ref += 1

                val newUri = if (first) {
                    uri
                } else {
                    uri.withQueryParam("__ref", ref.toString())
                }

                first = false

                val headword = Word(
                    tag, word, word, word, page, newUri,
                    finalBaseUrl,
                    doc,
                    doc.head().html() + "<body>",
                    lemma,
                    xrefs = arrayListOf(ref.toString()),
                    renderAsJson = true
                )

                val cat = lemma.selectFirst("span.d_cat")?.text()?.trim() ?: ""

                // Audio
                lemma.select("audio source").forEach { source ->
                    val src = source.attr("src")
                    if (src.isNotEmpty()) {
                        val url = if (src.startsWith("http")) src
                        else finalBaseUrl.trimEnd('/') + src
                        headword.audio.add(url)
                    }
                }

                // Parse definitions from the definition block.
                val defs = ArrayList<Word.Definition>()
                val idioms = ArrayList<Word.Idiom>()

                lemma.selectFirst("div.d_ptma")?.let { ptma ->
                    collectSenses(ptma, cat, defs, idioms)
                }

                headword.definitions.addAll(defs)
                headword.idioms.addAll(idioms)

                // Attach matching synonyms to the first definition.
                val synKey = cat.lowercase()
                if (synKey.isNotEmpty() && defs.isNotEmpty()) {
                    synByPos[synKey]?.let { syns ->
                        defs[0].synonyms.addAll(syns)
                    }
                }

                words.add(headword)
                lemma.remove()
            }

            if (words.size > 1) {
                val homographs = words.map { SearchResult(it.mTitle, it.summary, it.uri) }
                val entries = Word.homonymEntries(words)

                for (w in words) {
                    w.mHomographs.addAll(homographs)
                    w.mHomonymEntries.addAll(entries)
                }
            }

            return words
        }

        /**
         * Recursively walks the `d_ptma` tree and collects flat definitions and
         * idioms. Each `d_dvn`, `d_dvl`, or bare `d_dfn` at any depth becomes a
         * definition; `d_dvt` blocks marked "locution" become idioms.
         */
        private fun collectSenses(
            element: Element,
            cat: String,
            defs: ArrayList<Word.Definition>,
            idioms: ArrayList<Word.Idiom>,
            inheritDomain: String = ""
        ) {
            var domain = inheritDomain

            for (child in element.children()) {
                when {
                    child.hasClass("d_dvr") -> {
                        val topic = child.selectFirst("span.d_dtr")?.text()
                            ?.removeSurrounding("(", ")")?.trim() ?: ""
                        if (topic.isNotEmpty()) domain = topic
                        collectSenses(child, cat, defs, idioms, domain)
                    }

                    child.hasClass("d_dvn") || child.hasClass("d_dvl") -> {
                        // d_dvn/d_dvl are nested containers that mirror d_ptma:
                        // they hold d_dfn, d_xpl, d_mta, d_dvt, and even deeper
                        // d_dvl nesting — recurse like d_dvr.
                        collectSenses(child, cat, defs, idioms, domain)
                    }

                    child.hasClass("d_dvt") -> {
                        val idiom = parseLocution(child, cat)
                        if (idiom != null) {
                            idioms.add(idiom)
                        } else {
                            val dfn = child.selectFirst("span.d_dfn")
                            if (dfn != null) {
                                defs.add(parseDfnSense(dfn, cat, domain))
                            }
                            if (defs.isNotEmpty()) {
                                child.select("span.d_xpl").forEach { xpl ->
                                    attachExample(defs.last(), xpl)
                                }
                            }
                        }
                    }

                    child.`is`("span.d_dfn") -> {
                        defs.add(parseDfnSense(child, cat, domain))
                    }

                    child.`is`("span.d_xpl") && defs.isNotEmpty() -> {
                        attachExample(defs.last(), child)
                    }
                    // Register markers (span.d_mta, e.g. "spécialement",
                    // "(dans quelques emplois)") precede their d_dfn and are
                    // captured by parseDfnSense from the preceding siblings.
                }
            }
        }

        private fun parseDfnSense(
            dfn: Element,
            cat: String,
            domain: String
        ): Word.Definition {
            val dfnText = dfn.text()
            val definition = Word.Definition(dfnText, dfn.clone())
            definition.domain = domain
            definition.pos = cat
            definition.grammar = cat
            definition.gender = genderOf(cat)

            val gloss = Word.Gloss()
            gloss.definition = dfnText
            gloss.grammar = cat
            gloss.gender = genderOf(cat)
            definition.glosses.add(gloss)

            // Register markers (span.d_mta) that precede this definition in the
            // sense group, e.g. "spécialement", "(dans quelques emplois)".
            val register = ArrayList<String>()
            var prev = dfn.previousElementSibling()
            while (prev != null && prev.`is`("span.d_mta")) {
                register.add(prev.text().trim())
                prev = prev.previousElementSibling()
            }
            if (register.isNotEmpty()) {
                definition.register = register.reversed().joinToString(" ")
            }

            // Collect sibling examples.
            var sibling = dfn.nextElementSibling()
            while (sibling != null && sibling.`is`("span.d_xpl")) {
                attachExample(definition, sibling)
                sibling = sibling.nextElementSibling()
            }

            return definition
        }

        private fun attachExample(def: Word.Definition, xpl: Element) {
            // If the d_xpl contains a d_lca (headword) + d_gls (gloss), it's a
            // compound expression. Store the full text as the example.
            val text = xpl.text()
            if (text.isNotEmpty()) {
                val gloss = def.glosses.firstOrNull() ?: Word.Gloss()
                gloss.examples.add(text)
                if (def.glosses.isEmpty()) def.glosses.add(gloss)
            }
        }

        private fun parseLocution(
            dvt: Element,
            cat: String
        ): Word.Idiom? {
            val marker = dvt.selectFirst("span.d_mtb")?.text()?.trim() ?: return null
            if (!marker.contains("locution")) return null

            val xpl = dvt.selectFirst("span.d_xpl") ?: return null
            val lca = xpl.selectFirst("span.d_lca") ?: return null
            val idiomName = lca.text().removeSuffix(":").removeSuffix(" :").trim()
            if (idiomName.isEmpty()) return null

            val glsText = xpl.selectFirst("span.d_gls")?.text() ?: ""
            val idiom = Word.Idiom(idiomName, glsText)

            val gloss = Word.Gloss()
            gloss.definition = glsText
            gloss.grammar = cat
            gloss.gender = genderOf(cat)

            // Extract register markers from the gloss text (e.g.
            // "au figuré et familier").
            dvt.select("span.d_mta").forEach { m ->
                val text = m.text().trim()
                if (text.isNotEmpty()) {
                    idiom.register = if (idiom.register.isEmpty()) text
                    else idiom.register + " " + text
                }
            }

            idiom.glosses.add(gloss)
            return idiom
        }

        private fun parseSynonyms(
            main: Element,
            baseUrl: String
        ): Map<String, ArrayList<Word.Synonym>> {
            val result = HashMap<String, ArrayList<Word.Synonym>>()
            main.selectFirst("section.syn")?.select("div.b")?.forEach { b ->
                val cat = b.selectFirst("span.s_cat")?.text()
                    ?.trim()?.lowercase() ?: return@forEach
                val synonyms = ArrayList<Word.Synonym>()
                b.select("div.s_gsyn span.s_syni a, div.s_gsyn span.s_syn a").forEach { a ->
                    val text = a.text()
                    val href = a.attr("href")
                    val absHref = if (href.startsWith("http")) href
                    else baseUrl.trimEnd('/') + href
                    synonyms.add(Word.Synonym(text, absHref))
                }
                if (synonyms.isNotEmpty()) {
                    result[cat] = synonyms
                }
            }
            return result
        }

        private fun genderOf(cat: String): String {
            val lower = cat.lowercase()
            return when {
                lower.contains("féminin") || lower.contains("feminin") -> Genders.FEMININE
                lower.contains("masculin") -> Genders.MASCULINE
                else -> ""
            }
        }
    }
}
