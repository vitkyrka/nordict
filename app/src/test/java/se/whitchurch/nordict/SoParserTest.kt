package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner


@RunWith(RobolectricTestRunner::class)
class SoParserTest {

    private fun getPage(url: String): String {
        val client = OkHttpClient()
        val request = Request.Builder()
                .url("http://localhost:8000/" + url.replace("https://", ""))
                .build()

        client.newCall(request).execute().use { response ->
            return response.body?.string() ?: ""
        }
    }

    @Test
    fun testMjugg() {
        val page = getPage("https://svenska.se/so/?id=33534")
        val words = SoParser.parse(page)

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.uri).isEqualTo("https://svenska.se/so/?id=33534&ref=lnr246130")

        assertThat(word.mTitle).isEqualTo("mjugg")

        assertThat(word.idioms).hasSize(1)

        assertThat(word.idioms[0].idiom).isEqualTo("le i mjugg")
        assertThat(word.idioms[0].definition).startsWith("le i smyg")

        assertThat(word.idioms[0].examples).hasSize(1)
        assertThat(word.idioms[0].examples[0]).startsWith("hon log")
    }

    @Test
    fun testGård() {
        val page = getPage("https://svenska.se/so/?id=18788")
        val words = SoParser.parse(page)

        assertThat(words).hasSize(1)
        val word = words[0]

        assertThat(word.uri).isEqualTo("https://svenska.se/so/?id=18788&ref=lnr176698")

        assertThat(word.mTitle).isEqualTo("gård")

        assertThat(word.definitions).hasSize(2)

        assertThat(word.definitions[0].definition).startsWith("lantegendom")
        assertThat(word.definitions[0].examples).hasSize(10)

        assertThat(word.definitions[1].definition).startsWith("större")
        assertThat(word.definitions[1].examples).hasSize(10)

        assertThat(word.idioms).hasSize(2)

        assertThat(word.idioms[1].definition).isEqualTo("lämna ifrån sig all (fast) egendom")
    }

    @Test
    fun testLugn() {
        val page = getPage("https://svenska.se/so/?id=30709")
        val words = SoParser.parse(page)

        assertThat(words).hasSize(2)

        assertThat(words[1].definitions[1].definition).startsWith("tillstånd av")
    }
}