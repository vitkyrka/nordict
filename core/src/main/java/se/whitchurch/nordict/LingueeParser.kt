package se.whitchurch.nordict

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class LingueeParser {
    companion object {

        /**
         * Linguee's `/portugues-ingles/search` response is an HTML fragment of
         * `.main_item` suggested matches, not JSON. Each item carries a relative
         * `/portugues-ingles/traducao/<word>.html` href that becomes the result's
         * word-page URL.
         */
        fun parseSearch(body: String, uriOf: (page: String) -> HttpUrl): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            val doc = Jsoup.parse(body)
            doc.select(".main_item").forEach {
                val href = it.attr("href")
                if (href.isEmpty()) return@forEach
                val title = it.text()
                if (title.isEmpty()) return@forEach
                results.add(SearchResult(title, uriOf(href)))
            }
            return results
        }

        fun parse(
            page: String,
            uri: HttpUrl,
            tag: String,
            baseUrl: String = "https://www.linguee.pt/"
        ): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val doc = Jsoup.parse(page, finalBaseUrl)

            doc.selectFirst(".l_header")?.remove()
            doc.selectFirst(".footer")?.remove()
            doc.selectFirst(".lMainNavbar")?.remove()
            doc.selectFirst(".l_deepl_ad_container")?.remove()

            var first = true
            var ref = 0

            doc.select("div.exact div.lemma").forEach { lemma ->
                ref += 1

                val newUri = if (first) {
                    uri
                } else {
                    uri.withQueryParam("__ref", ref.toString())
                }

                first = false

                val taglemma = lemma.selectFirst("span.tag_lemma") ?: return@forEach
                val word = taglemma.select("a.dictLink").map { it.text() }.joinToString(" ")

                val grammar = lemma.selectFirst(".tag_wordtype")?.text()?.trim() ?: ""
                val meanings =
                    lemma.select("div.translation.featured span.tag_trans a.dictLink")
                        .map { it.text() }.joinToString("; ")

                val summary = StringBuilder(word)
                if (grammar.isNotEmpty()) {
                    summary.append(" ($grammar)")
                }
                if (meanings.isNotEmpty()) {
                    summary.append(" $meanings")
                }

                val headword = Word(
                    tag, word, word, summary.toString(), newUri,
                    xrefs = arrayListOf(ref.toString())
                )

                val gender = genderOf(grammar)

                lemma.select("a.audio").forEach {
                    val id = it.attr("id")

                    if (!id.startsWith("PT_PT")) {
                        return@forEach
                    }

                    headword.audio.add("${finalBaseUrl}mp3/$id.mp3")
                }

                // Each featured translation is its own definition sense: the
                // English headword is the gloss, joined with the entry's
                // Portuguese grammar/gender label.
                lemma.select("div.translation.sortablemg.featured").forEach { translation ->
                    val transText = translation.selectFirst("span.tag_trans a.dictLink")
                        ?.text()?.trim() ?: return@forEach
                    if (transText.isEmpty()) return@forEach

                    val definition = Word.Definition(transText, translation.clone())
                    definition.pos = grammar
                    definition.grammar = grammar
                    definition.gender = gender

                    val gloss = Word.Gloss()
                    gloss.definition = transText
                    gloss.grammar = grammar
                    gloss.gender = gender

                    // Bilingual examples: "Portuguese sentence — English sentence".
                    translation.select("div.example").forEach { ex ->
                        val pt = ex.selectFirst(".tag_s")?.text() ?: ""
                        val en = ex.selectFirst(".tag_t")?.text() ?: ""
                        val example = if (en.isEmpty()) pt else "$pt — $en"
                        if (example.isNotBlank()) {
                            gloss.examples.add(example)
                        }
                    }

                    definition.glosses.add(gloss)
                    definition.examples.addAll(gloss.examples)
                    headword.definitions.add(definition)
                }

                lemma.remove()
                words.add(headword)
            }

            if (words.size > 1) {
                val entries = Word.homonymEntries(words)

                for (word in words) {
                    word.mHomonymEntries.addAll(entries)
                }
            }

            return words
        }

        private fun genderOf(grammar: String): String {
            val lower = grammar.lowercase()
            return when {
                lower.contains("feminino") -> Genders.FEMININE
                lower.contains("masculino") -> Genders.MASCULINE
                else -> ""
            }
        }
    }
}