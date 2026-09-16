package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import java.io.File

class SdoParserTest {
    private val skaffaUrl =
        "https://ws.dsl.dk/sdo/query?app=android&version=2.1.5&q=skaffa".toHttpUrl()!!
    private val husUrl =
        "https://ws.dsl.dk/sdo/query?app=android&version=2.1.5&q=hus".toHttpUrl()!!

    @Test
    fun testSkaffaGolden() {
        val page = File("../testdata/sdo/skaffa.html").readText()
        val words = SdoParser.parse(page, skaffaUrl, "SDO", "https://ordnet.dk/sdo/")
        Goldens.assertGolden(
            words.map { it.toWordData() },
            "../testdata/sdo/skaffa.json",
            Array<WordJson.WordData>::class.java
        )

        val word = words.single()
        assertThat(word.mTitle).isEqualTo("skaffa")
        assertThat(word.renderAsJson).isTrue()
        assertThat(word.pos).isEqualTo(Pos.VERB)
        // "-r, -de, -t" become conjugated with the stem filled in.
        assertThat(word.conjugation).isEqualTo("skaffar, skaffade, skaffat")
        assertThat(word.pronunciation).isEmpty()

        // 2 numbered senses; the bullet sub-senses carry the parent number.
        assertThat(word.definitions.map { it.senseNumber })
            .containsExactly("1", "1.a", "1.b", "2").inOrder()
        val definition = word.definitions[3]
        assertThat(definition.glosses.single().definition).isEqualTo("skaffe (spise)")
        // `.spec` marker text goes to the definition marker slots.
        assertThat(definition.domain).isEqualTo("søf.")

        // The bilingual example joins the Swedish sentence and Danish rendition.
        val gloss = word.definitions[1].glosses.single()
        assertThat(gloss.examples)
            .containsExactly(
                "hon skaffade sig en cykel — hun anskaffede (sig) en cykel",
                "de tänker skaffa barn — de tænker på at få børn"
            )

        // Idioms: locution title plus the Danish rendition as gloss.
        assertThat(word.idioms.map { it.idiom })
            .containsExactly("skaffa fram", "skaffa undan").inOrder()
        assertThat(word.idioms[0].glosses.single().definition).isEqualTo("fremskaffe")
    }

    @Test
    fun testHusGolden() {
        val page = File("../testdata/sdo/hus.html").readText()
        val words = SdoParser.parse(page, husUrl, "SDO", "https://ordnet.dk/sdo/")
        Goldens.assertGolden(
            words.map { it.toWordData() },
            "../testdata/sdo/hus.json",
            Array<WordJson.WordData>::class.java
        )

        val word = words.single()
        assertThat(word.mTitle).isEqualTo("hus")
        assertThat(word.pos).isEqualTo(Pos.NOUN)
        assertThat(word.conjugation).isEqualTo("huset, hus, husen")

        // A single unnumbered sense with example sentences.
        assertThat(word.definitions.map { it.senseNumber }).containsExactly("")
        assertThat(word.definitions[0].glosses.single().examples).isNotEmpty()

        // Idioms: registers (`dagl.`, `gl.`, `bibelsk`) attach to the senses.
        assertThat(word.idioms).hasSize(11)
        val dagl = word.idioms.first()
        assertThat(dagl.idiom).isEqualTo("det tar hus i helvete/helsike")
        assertThat(dagl.register).isEqualTo("dagl.")
        val bibelsk = word.idioms[word.idioms.size - 2]
        assertThat(bibelsk.idiom).isEqualTo("se/beställa om sitt hus")
        assertThat(bibelsk.register).isEqualTo("bibelsk")
        assertThat(bibelsk.glosses.single().definition).isEqualTo("beskikke sit hus")
    }

    @Test
    fun testParseSearch() {
        val results = SdoParser.parseSearch("""["hus","husa","husar"]""") {
            "https://ws.dsl.dk/sdo/query?app=android&version=2.1.5&q=$it".toHttpUrl()!!
        }

        assertThat(results).hasSize(3)
        assertThat(results.map { it.mTitle })
            .containsExactly("hus", "husa", "husar").inOrder()
        assertThat(results[0].uri.toString())
            .isEqualTo("https://ws.dsl.dk/sdo/query?app=android&version=2.1.5&q=hus")
    }
}