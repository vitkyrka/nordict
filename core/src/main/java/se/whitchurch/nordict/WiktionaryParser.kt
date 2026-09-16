package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class WiktionaryParser {
    companion object {

        /**
         * Decodes the Wiktionary REST API `/v1/search/title` response.
         * The JSON shape is `{"pages":[{"title","id"}]}`.
         */
        fun parseSearch(
            body: String,
            shortName: String,
            uriOf: (id: Int, title: String) -> HttpUrl
        ): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            try {
                val pages = JsonParser.parseString(body).asJsonObject.getAsJsonArray("pages")
                for (el in pages) {
                    if (!el.isJsonObject) continue
                    val page = el.asJsonObject
                    val title = page.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: continue
                    val id = page.get("id")?.takeIf { it.isJsonPrimitive }?.asInt
                        ?: continue
                    results.add(SearchResult(title, uriOf(id, title)))
                }
            } catch (_: Exception) {
            }
            return results
        }

        fun parse(
            page: String,
            uri: HttpUrl,
            tag: String,
            shortName: String,
            baseUrl: String = "https://${shortName}.m.wiktionary.org"
        ): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val doc = Jsoup.parse(page, finalBaseUrl)

            val heading = doc.selectFirst("#section_0")
                ?: doc.selectFirst("#firstHeading")
                ?: return words

            val word = heading.text()
            val element = doc.selectFirst("#bodyContent") ?: return words
            val content = element.outerHtml()
            val cleanpage = doc.head().html() + "<body>" + content

            // Remove other languages: find the language section that contains
            // a span with id matching shortName (e.g. #fr).
            // In the current layout, .mw-parser-output children are <section>
            // elements, each wrapping one language.
            val mwo = element.selectFirst(".mw-parser-output") ?: return words
            var langSection: Element? = null

            for (child in mwo.children()) {
                val langSpan = child.selectFirst("#$shortName")
                if (langSpan != null) {
                    langSection = child
                    // Mark children as open-block for the renderer
                    child.addClass("open-block")
                    break
                }
                child.remove()
            }

            if (langSection == null) {
                // Fallback: try the old approach where h3 headings were
                // direct children of a section
                return parseLegacy(element, mwo, uri, tag, shortName, finalBaseUrl, doc, word, cleanpage)
            }

            // Remove translations (Traductions sections)
            langSection.selectFirst("section h3 span[id^=Traductions]")?.let { trad ->
                var ancestor = trad.parent()
                while (ancestor != null && ancestor.tagName() != "section") {
                    ancestor = ancestor.parent()
                }
                ancestor?.remove()
            }

            // Handle lazy images
            element.select("span.lazy-image-placeholder").forEach { it.remove() }
            element.select("noscript").forEach { noscript ->
                val outside = noscript.parent() ?: return@forEach
                noscript.select("img").forEach { img ->
                    val src = "https:${img.attr("src")}"
                    if (!src.contains("upload.wikimedia.org")) return@forEach
                    img.attr("src", src)
                    img.appendTo(outside)
                }
            }

            val images = ArrayList<String>()
            element.select("img[src*=upload.wikimedia.org]").forEach {
                images.add(it.attr("src"))
            }

            // Extract pronunciation and etymology from sub-sections.
            var pronunciationText = ""
            var pronunciationAudio = ArrayList<String>()
            var etymologyText = ""

            for (sub in langSection.select("section")) {
                val h3 = sub.selectFirst("h3") ?: continue

                when {
                    h3.selectFirst("span.titrepron") != null -> {
                        pronunciationText = extractPronunciationText(sub)
                        pronunciationAudio = extractAudio(sub, finalBaseUrl)
                    }
                    h3.selectFirst("span.titreetym") != null -> {
                        etymologyText = extractEtymologyText(sub)
                    }
                }
            }

            // Collect definition sections (h3 with span.titredef)
            val defSections = ArrayList<Element>()
            for (sub in langSection.select("section")) {
                val h3 = sub.selectFirst("h3") ?: continue
                if (h3.selectFirst("span.titredef") != null) {
                    defSections.add(sub)
                }
            }

            var first = true
            for (defSection in defSections) {
                val h3 = defSection.selectFirst("h3") ?: continue
                val titledef = h3.selectFirst("span.titredef") ?: continue

                val pos = titledef.text()
                val ref = titledef.id() ?: continue

                val newUri = if (first) {
                    uri
                } else {
                    uri.withQueryParam("__ref", ref)
                }
                first = false

                val headword = Word(
                    tag, word, word, "$word $pos", newUri,
                    xrefs = arrayListOf(ref)
                )

                headword.images.addAll(images)
                headword.pos = posToEnum(pos)
                headword.rawHeadword = word
                headword.pronunciation = pronunciationText
                headword.etymology = etymologyText

                // Extract gender from the lemma paragraph
                val genderWord = extractGender(defSection)
                headword.gender = genderOf(genderWord)

                // Audio
                headword.audio.addAll(pronunciationAudio)

                // Parse definitions from the <ol> list
                val ol = defSection.selectFirst("ol") ?: continue
                for (li in ol.children()) {
                    parseDefinition(headword, li, pos, genderWord)
                }

                words.add(headword)
            }

            // Also check for inline definitions (ol > li not inside a sub-section)
            // If no defSections found but there's an ol in the langSection, try
            // that as a single definition section
            if (words.isEmpty()) {
                val ol = langSection.selectFirst("ol") ?: return words
                val ref = "def"
                val headword = Word(
                    tag, word, word, word, uri,
                    xrefs = arrayListOf(ref)
                )
                headword.images.addAll(images)
                headword.pronunciation = pronunciationText
                headword.etymology = etymologyText
                headword.audio.addAll(pronunciationAudio)

                for (li in ol.children()) {
                    parseDefinition(headword, li, "", "")
                }
                words.add(headword)
            }

            if (words.size > 1) {
                val entries = Word.homonymEntries(words)

                for (w in words) {
                    w.mHomonymEntries.addAll(entries)
                }
            }

            return words
        }

        private fun parseLegacy(
            element: Element,
            mwo: Element,
            uri: HttpUrl,
            tag: String,
            shortName: String,
            baseUrl: String,
            doc: org.jsoup.nodes.Document,
            word: String,
            cleanpage: String
        ): List<Word> {
            // Fallback for old HTML where h3 headings are direct children
            // of the first section element
            val words: ArrayList<Word> = ArrayList()
            var preserve = false
            mwo.children().forEach {
                if (preserve) {
                    it.addClass("open-block")
                } else {
                    it.remove()
                }
                preserve = false
                it.selectFirst("#$shortName")?.let { preserve = true }
            }

            val lemmas = ArrayList<Element>()
            var current = Element("div")
            var etymology: Element? = null
            var pronunciation: Element? = null
            element.selectFirst("section")?.children()?.forEach {
                if (it.tagName() == "h3") {
                    current = Element("div")
                    when {
                        it.selectFirst("span.titreetym") != null -> etymology = current
                        it.selectFirst("span.titrepron") != null -> pronunciation = current
                        it.selectFirst("span.titredef") != null -> lemmas.add(current)
                    }
                }
                current.appendChild(it)
            }

            var first = true
            lemmas.forEach { lemma ->
                val titledef = lemma.selectFirst(".titredef") ?: return@forEach
                val pos = titledef.text()
                val ref = titledef.id() ?: return@forEach

                val newUri = if (first) uri else uri.withQueryParam("__ref", ref)
                first = false

                val headword = Word(
                    tag, word, word, "$word $pos", newUri,
                    xrefs = arrayListOf(ref)
                )
                headword.rawHeadword = word

                pronunciation?.select("audio")?.forEach { audio ->
                    val source = audio.selectFirst("source") ?: return@forEach
                    val url = source.attr("src")
                    if (url.isNotEmpty()) {
                        headword.audio.add(if (url.startsWith("http")) url else "https:$url")
                    }
                }

                lemma.selectFirst("ol")?.children()?.forEach { li ->
                    parseDefinition(headword, li, pos, "")
                }

                words.add(headword)
            }

            if (words.size > 1) {
                val entries = Word.homonymEntries(words)
                for (w in words) {
                    w.mHomonymEntries.addAll(entries)
                }
            }

            return words
        }

        private fun parseDefinition(
            headword: Word,
            li: Element,
            pos: String,
            genderWord: String
        ) {
            val clone = li.clone()
            // Examples are collected separately below.
            clone.select("ul").forEach { it.remove() }

            // Extract domain from span.term
            val term = li.selectFirst("span.term .texte")
            // Extract register from span.emploi
            val emploi = li.selectFirst("span.emploi .texte")

            var defText = clone.text().trim()
            if (defText.isEmpty()) return

            // Strip the leading "(Mobilier)"/"(En particulier)" marker from the
            // gloss, since it's carried separately as domain/register.
            if (term != null) {
                defText = defText.removePrefix("(${term.text().trim()})").trim()
            }
            if (emploi != null) {
                defText = defText.removePrefix("(${emploi.text().trim()})").trim()
            }

            val definition = Word.Definition(defText, li)
            definition.pos = pos
            definition.grammar = pos
            definition.gender = genderOf(genderWord)

            if (term != null) {
                definition.domain = term.text().trim()
            }
            if (emploi != null) {
                definition.register = emploi.text().trim()
            }

            val gloss = Word.Gloss()
            gloss.definition = defText
            gloss.grammar = pos
            gloss.gender = genderOf(genderWord)

            // Extract examples from the nested ul
            li.selectFirst("ul")?.children()?.forEach { exampleLi ->
                val exampleText = exampleLi.selectFirst("q")?.text()
                    ?: exampleLi.text()
                if (exampleText.contains("Exemple d'utilisation manquant")) return@forEach
                if (exampleText.isNotEmpty()) {
                    gloss.examples.add(exampleText.trim())
                }
            }

            definition.glosses.add(gloss)
            headword.definitions.add(definition)
        }

        private fun extractGender(defSection: Element): String {
            val ligneDeForme = defSection.selectFirst("span.ligne-de-forme")
            return ligneDeForme?.text() ?: ""
        }

        private fun extractPronunciationText(section: Element): String {
            val text = section.text()
            // Extract the phonetic transcription between \...\ or IPA in [...]
            val match = Regex("""\\([^\\]+)\\""").find(text)
                ?: Regex("""\[([^\]]+)]""").find(text)
            return match?.value ?: ""
        }

        private fun extractEtymologyText(section: Element): String {
            // Get all text content after the heading
            val content = StringBuilder()
            for (child in section.children()) {
                if (child.tagName() == "div" && child.hasClass("mw-heading")) continue
                content.append(child.text())
            }
            return content.toString().trim()
        }

        private fun extractAudio(section: Element, baseUrl: String): ArrayList<String> {
            val audio = ArrayList<String>()
            section.select("audio source").forEach { source ->
                val src = source.attr("src")
                if (src.isEmpty()) return@forEach
                // Keep only the playable mp3 transcodes, drop ogg/wav originals
                if (!src.contains(".mp3")) return@forEach
                val url = if (src.startsWith("http")) src
                else if (src.startsWith("//")) "https:$src"
                else baseUrl.trimEnd('/') + src
                if (url !in audio) audio.add(url)
            }
            return audio
        }

        private fun posToEnum(pos: String): Pos {
            val lower = pos.lowercase()
            return when {
                lower.contains("nom commun") || lower.contains("nom propre") -> Pos.NOUN
                lower.contains("verbe") -> Pos.VERB
                lower.contains("adjectif") || lower.contains("adjectif") -> Pos.ADJECTIVE
                lower.contains("adverbe") -> Pos.ADVERB
                lower.contains("préposition") -> Pos.PREPOSITION
                lower.contains("conjonction") -> Pos.CONJUNCTION
                lower.contains("pronom") -> Pos.PRONOUN
                lower.contains("interjection") -> Pos.INTERJECTION
                else -> Pos.UNKNOWN
            }
        }

        private fun genderOf(genderText: String): String {
            val lower = genderText.lowercase()
            return when {
                lower.contains("féminin") && !lower.contains("masculin") -> Genders.FEMININE
                lower.contains("masculin") && !lower.contains("féminin") -> Genders.MASCULINE
                lower.contains("masculin") && lower.contains("féminin") -> ""
                else -> ""
            }
        }
    }
}
