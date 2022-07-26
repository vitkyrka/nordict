package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup

class CollinsParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc =
                Jsoup.parse(page, "https://www.collinsdictionary.com/dictionary/french-english/")

            var main = doc.selectFirst("main") ?: return words
            val word = main.selectFirst("span.orth")?.text() ?: return words
            val cleanpage = doc.head().html() + "<body>" + main

            main.selectFirst("div.topslot_container")?.remove()

            val headword = Word(
                tag, word, word, word.toString(), cleanpage, uri,
                "https://www.collinsdictionary.com/",
                main,
                doc.head().html() + "<body>"
            )

            main.select("div.he a.sound")?.forEach audio@{ audio ->
                val src = audio.attr("data-src-mp3")

                headword.audio.add(src)
            }

            words.add(headword)
            return words
        }
    }
}