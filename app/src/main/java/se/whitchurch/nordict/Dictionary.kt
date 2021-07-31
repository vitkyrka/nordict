package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

abstract class Dictionary(val client: OkHttpClient) {
    abstract val tag: String
    abstract val flag: Int
    abstract val lang: String
    abstract fun init()
    abstract fun search(query: String): List<SearchResult>
    abstract fun fullSearch(query: String): List<SearchResult>
    abstract fun get(uri: Uri): Word?

    fun fetch(pageUrl: String): String {
        val request = Request.Builder().url(pageUrl).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e("Dictionary", "Unexpected response: " + response.code)
            return ""
        }

        Log.i("url", pageUrl)

        return response.body?.string() ?: ""
    }
}