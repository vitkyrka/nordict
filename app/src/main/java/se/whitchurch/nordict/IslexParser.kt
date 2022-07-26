package se.whitchurch.nordict

import android.net.Uri
import org.json.JSONObject
import org.jsoup.Jsoup

class IslexParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val obj = JSONObject(page)

            val word = obj.getString("fletta")

            val headword = Word(
                tag, word, word, word.toString(), page, uri,
                "https://islex.arnastofnun.is/is/ord/",
                Jsoup.parse(page),
                "",
            )

            words.add(headword)
            return words
        }
    }
}