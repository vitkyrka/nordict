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

            // Materialize headword metadata first so __ref URIs can be assigned
            // after the default (main-first) ordering is known.
            data class Head(val title: String, val isMain: Boolean, val label: String, val ref: String, val block: Element)

            var ref = 0
            val heads = ArrayList<Head>()
            doc.select("div.cB.cB-def").forEach { block ->
                ref += 1

                val title = block.selectFirst("h2.h2_entry .orth")?.text()?.trim()
                    ?: return@forEach
                val isMain = block.hasClass("benedict")
                if (!isMain && !block.hasClass("easy")) return@forEach
                if (block.selectFirst("div.content.definitions") == null) return@forEach

                heads.add(Head(title, isMain, if (isMain) mainLabel else easyLabel, ref.toString(), block))
            }

            // Default (no-__ref) view shows the main dictionary headword first;
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
                headword.dictionary = head.label

                head.block.select("div.mini_h2 a.hwd_sound[data-src-mp3]").forEach { audio ->
                    headword.audio.add(audio.attr("data-src-mp3"))
                }

                parseDefinitions(head.block, headword)
                words.add(headword)
            }

            if (words.size > 1) {
                val homographs = words.map { SearchResult(it.mTitle, it.summary, it.uri) }
                for (word in words) {
                    word.mHomographs.addAll(homographs)
                }
            }

            return words
        }

        private const val REFPARAM = CollinsDictionary.REFPARAM

        private fun parseDefinitions(block: Element, headword: Word) {
            val content = block.selectFirst("div.content.definitions") ?: return
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

            // Idioms and phrases nested at any depth inside the hom are hoisted
            // to the definition (the user-facing grouping), then pruned so they
            // don't also appear inline inside a sense's rich HTML.
            hom.select("div.re.type-idm").forEach { re ->
                definition.idioms.add(parsePhrase(re))
                re.remove()
            }
            hom.select("div.re.type-phr").forEach { re ->
                definition.phrases.add(parsePhrase(re))
                re.remove()
            }

            hom.children().forEach { child ->
                if (child.tagName() == "div" && child.hasClass("sense")) {
                    definition.glosses.add(parseSense(child))
                }
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