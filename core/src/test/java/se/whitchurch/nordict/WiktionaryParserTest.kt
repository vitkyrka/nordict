package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import java.io.File

/**
 * Pure-JVM golden tests for the Wiktionary parser. Maps parsed `Word`s
 * through the exact same `WordJson.toWordData()` mapping the desktop CLI
 * emits, so the app, the CLI, and the test suite all agree on one schema.
 *
 * The fixture is the French Wiktionary mobile page for "table"
 * (fr.m.wiktionary.org), which yields two homograph entries: the `Nom
 * commun` lemma (with gender, domain/register markers, examples) and the
 * `Forme de verbe` lemma. Pronunciation + audio and the etymology live in
 * their own sections and are attached to each lemma.
 */
class WiktionaryParserTest {

    private fun assertGolden(words: List<Word>, jsonPath: String) {
        Goldens.assertGolden(words.map { it.toWordData() }, jsonPath, Array<WordJson.WordData>::class.java)
    }

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun testParseTable() {
        val htmlFile = File("../testdata/wfr/table.html")
        val page = htmlFile.readText()
        val uri = httpUrl("https://fr.m.wiktionary.org/wiki/table")
        val words = WiktionaryParser.parse(page, uri, "WFR", "fr")

        // Two homographs: Nom commun + Forme de verbe.
        assertThat(words).hasSize(2)
        val word = words[0]

        assertThat(word.mTitle).isEqualTo("table")
        assertThat(word.rawHeadword).isEqualTo("table")
        assertThat(word.pronunciation).isEqualTo("\\tabl\\")
        assertThat(word.etymology).contains("Du latin")
        assertThat(word.audio).isNotEmpty()
        assertThat(word.audio[0]).contains("mp3")

        // Gender from the lemma's span.ligne-de-forme.
        assertThat(word.gender).isEqualTo(Genders.FEMININE)
        assertThat(word.definitions).hasSize(13)
        assertThat(word.definitions[0].pos).isEqualTo("Nom commun")
        assertThat(word.definitions[0].grammar).isEqualTo("Nom commun")
        assertThat(word.definitions[0].glosses[0].gender).isEqualTo(Genders.FEMININE)

        // Domain marker from span.term.
        val def0 = word.definitions[0]
        assertThat(def0.domain).isEqualTo("Mobilier")
        assertThat(def0.glosses[0].definition).contains("Surface plane de bois")
        // The "(Mobilier)" prefix is stripped from the gloss.
        assertThat(def0.glosses[0].definition).doesNotContain("(Mobilier)")
        assertThat(def0.glosses[0].examples).isNotEmpty()
        assertThat(def0.glosses[0].examples[0]).contains("cueilleurs")

        // Register marker from span.emploi.
        val def1 = word.definitions[1]
        assertThat(def1.register).isEqualTo("En particulier")
        assertThat(def1.glosses[0].definition).startsWith("Table à manger")

        // Homograph refs: each lemma resolves via its __ref.
        assertThat(word.xrefs).containsExactly("fr-nom-1")
        assertThat(word.uri.toString()).doesNotContain("__ref")
        assertThat(words[1].xrefs).containsExactly("fr-flex-verb-1")
        assertThat(words[1].uri.toString())
            .isEqualTo("https://fr.m.wiktionary.org/wiki/table?__ref=fr-flex-verb-1")
        assertThat(words[1].definitions).isNotEmpty()
        assertThat(words[1].definitions[0].glosses[0].definition)
            .isEqualTo("Première personne du singulier du présent de l’indicatif de tabler.")

        // Every page word carries the full renderable entry list (page order,
        // itself included) so the renderer can draw all entries in one page.
        for (w in words) {
            assertThat(w.mHomonymEntries).hasSize(2)
            assertThat(w.mHomonymEntries.map { it.mTitle }).containsExactly("table", "table").inOrder()
            assertThat(w.mHomonymEntries.map { it.ref })
                .containsExactly("fr-nom-1", "fr-flex-verb-1").inOrder()
        }
        assertThat(word.mHomonymEntries[0].definitions).hasSize(13)
        assertThat(word.mHomonymEntries[1].definitions[0].glosses[0].definition)
            .isEqualTo("Première personne du singulier du présent de l’indicatif de tabler.")

        assertGolden(words, "../testdata/wfr/table.json")
    }

    @Test
    fun testParseSearch() {
        val body = File("../testdata/wfr-search.json").readText()

        val results = WiktionaryParser.parseSearch(body, "fr") { id, title ->
            httpUrl("https://fr.m.wiktionary.org/?curid=$id")
        }

        assertThat(results).hasSize(10)
        assertThat(results[0].mTitle).isEqualTo("table")
        assertThat(results[0].uri.toString())
            .isEqualTo("https://fr.m.wiktionary.org/?curid=774")
        assertThat(results[1].mTitle).isEqualTo("tableau")
    }

    @Test
    fun testParseSearchToleratesNonObjectBodies() {
        val results = WiktionaryParser.parseSearch("not json", "fr") { id, title ->
            httpUrl("https://fr.m.wiktionary.org/?curid=$id")
        }

        assertThat(results).isEmpty()
    }
}