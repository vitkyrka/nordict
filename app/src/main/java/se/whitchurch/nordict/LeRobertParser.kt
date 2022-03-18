package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.math.max

class LeRobertParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc = Jsoup.parse(page, "https://dictionnaire.lerobert.com/")


            var main = doc.selectFirst("div.ws-c") ?: return words
            val word = main.selectFirst("h1")?.text() ?: return words
            val cleanpage = doc.head().html() + "<body>" + main

            main.selectFirst("div.ws-a")?.remove()

            Log.i("foo", cleanpage);

            var first = true
            var ref = 0
            main.select("section.def")?.select("div.b")?.forEach lemma@{ lemma ->
                ref += 1

                val newUri = if (first) {
                    uri
                } else {
                    uri.buildUpon().appendQueryParameter("__ref", ref.toString()).build()
                }

                first = false

                val headword = Word(
                    tag, word, word, word.toString(), cleanpage, newUri,
                    "https://dictionnaire.lerobert.com/",
                    main,
                    doc.head().html() + "<body>",
                    lemma
                )

                headword.xrefs.add(ref.toString())

                lemma.select("audio")?.forEach audio@{ audio ->
                    val source = audio.selectFirst("source") ?: return@audio
                    val src = source.attr("src")
                    val url = "https://dictionnaire.lerobert.com$src"

                    headword.audio.add(url)
                }

                lemma.select("div.d_ptma")?.select("div.d_dvn")?.forEach dvn@ { dvn ->
                    val def = Word.Definition(dvn.text(), dvn)

                    dvn.remove()
                    headword.definitions.add(def)
                }

                if (headword.definitions.isEmpty()) {
                    var dvn = Element("div")

                    lemma.selectFirst("div.d_ptma")?.children()?.forEach {
                        dvn.appendChild(it)
                    }

                    val def = Word.Definition(dvn.text(), dvn)
                    headword.definitions.add(def)
                }

                lemma.remove()
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
    }
}