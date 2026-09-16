package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import java.io.File

class SoParserTest {
    private val husUrl =
        "https://svenska.se/api/article/so/185690".toHttpUrl()!!
    private val kutterUrl =
        "https://svenska.se/api/article/so/221211".toHttpUrl()!!

    @Test
    fun testHusGolden() {
        val page = File("../testdata/so/hus.article.json").readText()
        val words = SoParser.parse(page, husUrl, "SO", "https://svenska.se")
        Goldens.assertGolden(
            words.map { it.toWordData() },
            "../testdata/so/hus.json",
            Array<WordJson.WordData>::class.java
        )

        val word = words.single()
        assertThat(word.mTitle).isEqualTo("hus")
        assertThat(word.pos).isEqualTo(Pos.NOUN)
        assertThat(word.conjugation)
            .isEqualTo("huset, plural hus, bestämd form plural husen, åld. dativ huse")
        // The head pronunciation stress entry is absent on this article.
        assertThat(word.pronunciation).isEmpty()
        assertThat(word.audio).containsExactly(
            "https://isolve-so-service.appspot.com/pronounce?id=185690_1.mp3"
        )

        // 3 numbered senses; the sub-senses are extra glosses on their parent.
        assertThat(word.definitions).hasSize(3)
        val first = word.definitions[0]
        assertThat(first.glosses).hasSize(4)
        assertThat(first.glosses[0].definition).startsWith("uppbyggd konstruktion")
        assertThat(first.glosses[0].examples)
            .containsExactly("bygga hus", "från det inre av huset hördes röster",
                "under semestern hyrde de ett hus på västkusten", "familjen har köpt ett gammalt hus med stor tomt")
            .inOrder()
        assertThat(first.glosses[1].definition).startsWith("spec. om offentlig el. gemensam byggnad")
        assertThat(first.glosses[1].definition).endsWith("(vanligen i sammansättn.)")
        assertThat(first.glosses[1].examples).containsExactly("Folkets hus", "husets vin")
        assertThat(first.register).isEmpty()

        // bite 2 adds the "särsk. om furstesläkt" definitionstillägg into the gloss.
        assertThat(word.definitions[2].glosses[0].definition)
            .isEqualTo("släkt med förnäma anor särsk. om furstesläkt")

        // Formcomment on a bare definition goes into the gloss too.
        assertThat(word.definitions[1].glosses[0].definition)
            .isEqualTo("naturligt slutet utrymme (vanligen i sammansättn.)")

        // 12 real idioms; the 5 cross-reference-only entries are dropped.
        assertThat(word.idioms).hasSize(12)
        assertThat(word.idioms.map { it.idiom })
            .containsExactly(
                "Guds hus", "Herrens hus", "hålla hus", "ha/hålla öppet hus",
                "gå på huset", "göra rent hus (med något)", "vad huset förmår",
                "ta hus i helvete/helsike", "sjuka hus", "fullt hus",
                "se om sitt hus", "äta någon ur huset"
            ).inOrder()
        val öppet = word.idioms[3]
        assertThat(öppet.glosses.single().definition)
            .isEqualTo("ta emot gäster eller besökare när som helst på dagen ofta dock mellan vissa klockslag")
        assertThat(öppet.glosses.single().examples).hasSize(1)
        val gåPå = word.idioms[4]
        assertThat(gåPå.register).isEqualTo("vardagligt")
        val göra = word.idioms[5]
        assertThat(göra.glosses).hasSize(2)
        assertThat(göra.glosses[0].definition).isEqualTo("helt avskaffa (något)")
        // "fullt hus" carries a second sense headed only by its definitionsinledare.
        val fullt = word.idioms[9]
        assertThat(fullt.glosses[1].definition).isEqualTo("äv. allmännare")
    }

    @Test
    fun testKutterGolden() {
        val page = File("../testdata/so/kutter.article.json").readText()
        val words = SoParser.parse(page, kutterUrl, "SO", "https://svenska.se")
        Goldens.assertGolden(
            words.map { it.toWordData() },
            "../testdata/so/kutter.json",
            Array<WordJson.WordData>::class.java
        )

        val word = words.single()
        assertThat(word.mTitle).isEqualTo("kutter")
        assertThat(word.pos).isEqualTo(Pos.NOUN)
        assertThat(word.pronunciation).isEqualTo("kutt´er")
        assertThat(word.definitions).hasSize(2)
        assertThat(word.definitions[0].glosses).hasSize(2)
        assertThat(word.definitions[0].glosses[0].definition)
            .isEqualTo("enmastat segelfartyg med två försegel")
        assertThat(word.definitions[0].glosses[1].definition)
            .startsWith("äv. om modernare fartyg")
        assertThat(word.definitions[1].glosses[0].definition)
            .isEqualTo("roterande skärverktyg vanligen för träbearbetning")
        assertThat(word.idioms).isEmpty()
    }

    @Test
    fun testParseSearch() {
        val body = File("../testdata/so-search.json").readText()
        val results = SoParser.parseSearch(body) { id ->
            "https://svenska.se/api/article/so/$id".toHttpUrl()!!
        }

        // All 7 `so` suggestions are kept (compound forms resolve to their base article).
        assertThat(results).hasSize(7)
        assertThat(results[0].mTitle).isEqualTo("kutter")
        assertThat(results[0].mSummary).isEqualTo("substantiv")
        assertThat(results[0].uri.toString())
            .isEqualTo("https://svenska.se/api/article/so/221211")
        assertThat(results[1].uri.toString())
            .isEqualTo("https://svenska.se/api/article/so/221211")
        assertThat(results.map { it.mTitle })
            .containsExactly(
                "kutter", "kutterrigg (kutter)", "kutterriggad (kutter)",
                "kutterriggad (rigga)", "kuttersmycke", "kutterspån (kutter)",
                "kuttertackling (kutter)"
            ).inOrder()
    }
}