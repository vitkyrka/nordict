package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class CollinsParser {
    companion object {
        // Human-readable labels for the two sub-dictionaries a Collins page can
        // carry, keyed by dictCode. "easy" is the learner's dictionary;
        // "benedict" is the main Collins bilingual dictionary.
        private const val EASY_LABEL_ES = "Collins Easy Learning"
        private const val MAIN_LABEL_ES = "Collins Spanish-English"
        private const val EASY_LABEL_FR = "Collins Easy Learning"
        private const val MAIN_LABEL_FR = "Collins French-English"

        fun parse(page: String, uri: Uri, tag: String, dictCode: String, baseUrl: String = "https://www.collinsdictionary.com"): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val baseRoot = "$baseUrl/dictionary/${dictCode}"
            val doc = Jsoup.parse(page, baseRoot)

            doc.select("script").forEach { it.remove() }
            doc.select("link[rel=preload]").forEach { it.remove() }
            doc.select("link[rel=preconnect]").forEach { it.remove() }
            doc.select("div.mpuslot_b-container").forEach { it.remove() }
            doc.select("div.carousel").forEach { it.remove() }
            doc.select("div.navigation").forEach { it.remove() }
            doc.select("div.topslot_container").forEach { it.remove() }
            doc.select("div.am-dictionary").forEach { it.remove() }

            if (doc.selectFirst("main") == null) return words

            val mainLabel = if (dictCode == "french-english") MAIN_LABEL_FR else MAIN_LABEL_ES
            val easyLabel = if (dictCode == "french-english") EASY_LABEL_FR else EASY_LABEL_ES

            // A single renderable unit: one headword. The main (benedict)
            // dictionary may carry several homographs (POS groups) under one
            // `div.cB`; each such hom becomes its own headword, just as the
            // easy-learning dictionary spreads its headwords across separate
            // `div.cB` blocks. `content` is the element whose `div.hom`
            // children (and any roaming idioms/phrases) get parsed.
            data class Head(
                val title: String,
                val isMain: Boolean,
                val label: String,
                val ref: String,
                val block: Element,
                val content: Element,
                val singleHom: Element? = null
            )

            var ref = 0
            val heads = ArrayList<Head>()
            doc.select("div.cB.cB-def").forEach { block ->
                // `data-xrentry` marks a main-dictionary entry embedded in a
                // cross-reference stub page (e.g. the full "ley" entry inside
                // "ley de la gravedad"). Unlike a plain `benedict` block it
                // carries neither the benedict nor the easy marker, but it is
                // still a first-class main headword.
                val isMain = block.hasClass("benedict") || block.hasAttr("data-xrentry")
                val isEasy = block.hasClass("easy")
                if (!isMain && !isEasy) return@forEach

                val title = block.selectFirst("h2.h2_entry .orth")?.text()?.trim()
                    ?: return@forEach
                val content = block.selectFirst("div.content.definitions")
                    ?: return@forEach
                val label = if (isMain) mainLabel else easyLabel

                if (isMain) {
                    // Each POS-group hom is a distinct headword.
                    val homs = content.children().filter { it.tagName() == "div" && it.hasClass("hom") }
                    if (homs.isEmpty()) {
                        ref += 1
                        heads.add(Head(title, true, label, ref.toString(), block, content))
                    } else {
                        for (hom in homs) {
                            ref += 1
                            heads.add(Head(title, true, label, ref.toString(), block, content, hom))
                        }
                    }
                } else {
                    ref += 1
                    heads.add(Head(title, false, label, ref.toString(), block, content))
                }
            }

            // Default (no-__ref) view shows the main dictionary headwords first;
            // easy-learning headwords follow. Order within each group is the
            // document order.
            heads.sortBy { if (it.isMain) 0 else 1 }

            heads.forEachIndexed { index, head ->
                // The first headword keeps the canonical (search-result) URL so
                // history/starring stay clean; every other headword resolves via
                // its own __ref.
                val headUri = if (index == 0) uri
                else uri.buildUpon().appendQueryParameter(REFPARAM, head.ref).build()

                val headword = Word(
                    tag, head.title, head.title, head.title, page, headUri,
                    baseRoot + "/",
                    doc,
                    "",
                    null,
                    xrefs = arrayListOf(head.ref),
                    renderAsJson = true
                )
                headword.rawHeadword = rawHeadword(head.title)
                headword.dictionary = head.label

                head.block.select("div.mini_h2 a.hwd_sound[data-src-mp3]").forEach { audio ->
                    headword.audio.add(audio.attr("data-src-mp3"))
                }

                if (head.singleHom != null) {
                    headword.definitions.add(parseHom(head.singleHom))
                } else {
                    parseContent(head.content, headword)
                }
                words.add(headword)
            }

            if (words.size > 1) {
                val homographs = words.map { SearchResult(it.mTitle, it.summary, it.uri) }
                val entries = Word.homonymEntries(words)

                for (word in words) {
                    word.mHomographs.addAll(homographs)
                    word.mHomonymEntries.addAll(entries)
                }
            }

            return words
        }

        private const val REFPARAM = CollinsDictionary.REFPARAM

        // Spanish determiners shown in Collins Easy Learning headwords ("la
        // frente") that the RAE dictionaries do not key on.
        private val ARTICLES = listOf("el ", "la ", "los ", "las ")

        // The bare headword form used when searching other dictionaries: strip a
        // leading article ("la frente" -> "frente"). Main-dictionary entries keep
        // their title unchanged (no article prefix to strip).
        private fun rawHeadword(title: String): String {
            for (article in ARTICLES) {
                if (title.startsWith(article)) {
                    return title.removePrefix(article).trim()
                }
            }
            return title
        }

        private fun parseContent(content: Element, headword: Word) {
            var currentDef: Word.Definition? = null

            content.children().forEach { child ->
                if (child.hasClass("hom")) {
                    currentDef = parseHom(child)
                    headword.definitions.add(currentDef)
                } else if (child.hasClass("re") && child.hasClass("type-idm")) {
                    currentDef?.idioms?.add(parsePhrase(child))
                } else if (child.hasClass("re") && child.hasClass("type-phr")) {
                    currentDef?.phrases?.add(parsePhrase(child))
                }
            }
        }

        private fun parseHom(hom: Element): Word.Definition {
            val definition = Word.Definition("", hom)

            val pos = hom.selectFirst(".gramGrp")?.text()
                ?.replace("Full verb table", "")?.replace(Regex("\\s+"), " ")?.trim() ?: ""
            definition.pos = pos
            definition.grammar = pos
            definition.gender = genderOf(pos)

            hom.children().forEach { child ->
                if (child.tagName() == "div" && child.hasClass("sense")) {
                    definition.glosses.add(parseSense(child))
                }
            }

            // A cross-reference stub (e.g. "ley de la gravedad" -> "law of
            // gravity") collapses the hom and its single sense into one element,
            // `<div class="hom sense">`, holding the translation directly rather
            // than nesting `<div class="sense">` children. Treat the hom itself
            // as the sense when it carries the `sense` class.
            if (definition.glosses.isEmpty() && hom.hasClass("sense")) {
                definition.glosses.add(parseSense(hom))
            }

            // Idioms and phrases living directly on the hom (roaming, outside any
            // sense) are hoisted to the POS-group definition. Those nested inside
            // a specific sense stay on that gloss, rendered inline under it.
            hom.select("div.re.type-idm").forEach { re ->
                definition.idioms.add(parsePhrase(re))
                re.remove()
            }
            hom.select("div.re.type-phr").forEach { re ->
                definition.phrases.add(parsePhrase(re))
                re.remove()
            }

            return definition
        }

        private fun parseSense(sense: Element): Word.Gloss {
            val gloss = Word.Gloss()

            // Examples are direct child .cit.type-example cells of the sense;
            // their inner HTML includes the source phrase, any English
            // translation(s), and inline register/geo markers.
            sense.children().forEach { child ->
                if (child.hasClass("cit") && child.hasClass("type-example")) {
                    gloss.examples.add(cleanHtml(child.html()))
                    child.remove()
                }
            }

            // Idioms and phrases nested inside this sense stay attached to the
            // gloss, then are pruned so they don't also appear in the rich HTML.
            sense.select("div.re.type-idm").forEach { re ->
                gloss.idioms.add(parsePhrase(re))
                re.remove()
            }
            sense.select("div.re.type-phr").forEach { re ->
                gloss.phrases.add(parsePhrase(re))
                re.remove()
            }

            // Definition HTML = the sense's remaining children: sensenum,
            // markers, nested sub-senses and translations.
            val definition = StringBuilder()
            sense.children().forEach { child ->
                definition.append(child.outerHtml())
            }
            gloss.definition = cleanHtml(definition.toString())
            return gloss
        }

        private fun parsePhrase(re: Element): Word.Phrase {
            val form = re.selectFirst(".form")
            val headword = form?.selectFirst(".orth")?.text()
                ?: form?.text()?.replace(Regex("^▪\\s*idiom:\\s*"), "")?.trim()
                ?: ""

            // Translation = the re children minus the headword form and any
            // example cells (which become the phrase's structured examples).
            val translation = StringBuilder()
            re.children().forEach { child ->
                if (child.hasClass("form")) return@forEach
                if (child.hasClass("cit") && child.hasClass("type-example")) return@forEach
                translation.append(child.outerHtml())
            }

            val phrase = Word.Phrase(headword, cleanHtml(translation.toString()))
            re.select("div.cit.type-example").forEach { ex ->
                phrase.examples.add(cleanHtml(ex.html()))
            }
            return phrase
        }

        // Collapse excess whitespace introduced around inline HTML while
        // preserving the structural markup.
        private fun cleanHtml(html: String): String {
            return html.replace(Regex(">\\s+<"), "> <").trim()
        }

        private fun genderOf(pos: String): String {
            return when {
                pos.contains("feminine") -> if (pos.contains("masculine")) "" else Genders.FEMININE
                pos.contains("masculine") -> Genders.MASCULINE
                else -> ""
            }
        }
    }
}