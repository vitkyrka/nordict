package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the Collins French-English parser — the shared
 * `CollinsParser` with `dictCode = "french-english"`. Maps parsed `Word`s
 * through the exact same `WordJson.toWordData()` mapping the desktop CLI
 * emits, so the app, the CLI, and the test suite all agree on one JSON schema.
 */
class ColfrenParserTest {

    private fun assertGolden(words: List<Word>, name: String) {
        Goldens.assertGolden(
            words.map { it.toWordData() },
            "../testdata/colfren/$name.json",
            Array<WordJson.WordData>::class.java
        )
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseTable() {
        val htmlFile = File("../testdata/colfren/table.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://www.collinsdictionary.com/dictionary/french-english/table")
        val words = CollinsParser.parse(page, uri, "COLFREN", "french-english")

        assertThat(words).hasSize(2)

        // Easy-learning headword first: the article is stripped from the
        // searchable headword, and its audio keeps the fr_ spelling.
        val easy = words[0]
        assertThat(easy.mTitle).isEqualTo("la table")
        assertThat(easy.rawHeadword).isEqualTo("table")
        assertThat(easy.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(easy.audio).hasSize(1)
        assertThat(easy.audio[0]).contains("/fr_")
        assertThat(easy.uri.toString()).doesNotContain("__ref")
        assertThat(easy.xrefs).containsExactly("1")
        assertThat(easy.definitions).hasSize(1)
        assertThat(easy.definitions[0].pos).isEqualTo("feminine noun")
        assertThat(easy.definitions[0].glosses).hasSize(1)
        assertThat(easy.definitions[0].glosses[0].definition).contains("table")
        // The easy entry's phrases roam at definition level (not nested in a sense).
        assertThat(easy.definitions[0].phrases).hasSize(5)
        assertThat(easy.definitions[0].phrases[0].headword).isEqualTo("mettre la table")

        // Main dictionary headword follows: the single feminine-noun hom.
        val main = words[1]
        assertThat(main.mTitle).isEqualTo("table")
        assertThat(main.dictionary).isEqualTo("Collins French-English")
        assertThat(main.audio).hasSize(1)
        assertThat(main.audio[0]).contains("/FR-")
        assertThat(main.definitions).hasSize(1)
        assertThat(main.definitions[0].pos).isEqualTo("feminine noun")
        assertThat(main.definitions[0].grammar).isEqualTo("feminine noun")
        assertThat(main.definitions[0].gender).isEqualTo(Genders.FEMININE)
        assertThat(main.definitions[0].idioms).isEmpty()
        assertThat(main.definitions[0].phrases).isEmpty()

        // The 6 phrases of the single sense stay nested in its gloss.
        val gloss = main.definitions[0].glosses.single()
        assertThat(gloss.examples).isEmpty()
        assertThat(gloss.idioms).isEmpty()
        assertThat(gloss.phrases).hasSize(6)
        assertThat(gloss.phrases.map { it.headword })
            .containsAtLeast("se mettre à table", "mettre la table", "faire table rase de")
        // The accent-less headword's orthographic form keeps the narrow
        // no-break space jsoup reports ("à table\u202f!").
        assertThat(gloss.phrases.any { it.headword.startsWith("à table") }).isTrue()
        assertThat(gloss.definition).contains("table")

        // Main headword follows via __ref; easy-learning keeps the canonical URL.
        assertThat(main.uri.toString()).contains("__ref=2")
        assertThat(main.xrefs).containsExactly("2")

        // Every word on the page carries the full renderable entry list (easy-
        // learning first, then main), itself included.
        for (w in words) {
            assertThat(w.mHomonymEntries).hasSize(2)
            assertThat(w.mHomonymEntries.map { it.ref }).containsExactly("1", "2").inOrder()
        }
        assertThat(main.mHomonymEntries.map { it.mTitle })
            .containsExactly("la table", "table").inOrder()
        assertThat(main.mHomonymEntries[0].dictionary).isEqualTo("Collins Easy Learning")

        assertGolden(words, "table")
    }

    @Test
    fun testParseSearch() {
        val body = File("../testdata/colfren-search.json").readText()

        val results = CollinsParser.parseSearch(body) { title ->
            httpUrl("https://www.collinsdictionary.com/dictionary/french-english/${title.replace(" ", "-").lowercase()}")
        }

        assertThat(results.map { it.mTitle })
            .containsExactly(
                "table", "table à abattant", "table à dessin", "table à langer",
                "table à repasser", "table basse"
            ).inOrder()
        assertThat(results[0].uri.toString())
            .isEqualTo("https://www.collinsdictionary.com/dictionary/french-english/table")
        assertThat(results[1].uri.toString())
            .isEqualTo("https://www.collinsdictionary.com/dictionary/french-english/table-%C3%A0-abattant")
    }

    @Test
    fun testParseSearchToleratesNonArrayBodies() {
        val results = CollinsParser.parseSearch("[1, 2]") { title ->
            httpUrl("https://www.collinsdictionary.com/dictionary/french-english/$title")
        }

        assertThat(results).isEmpty()
    }
}