package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the card pipeline ([Cards]): card proposals, Back HTML
 * building (fragment mode for JSON dictionaries, page-skeleton mode for
 * legacy dictionaries, combining for multi-dictionary words) and the note
 * fields. Parser fixtures are the same ones the parser golden tests use.
 */
class CardsTest {

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    private fun parseDleOtro(): Word {
        val page = File("../testdata/dle/otro.html").readText()
        return DleParser.parse(page, httpUrl("https://dle.rae.es/otro"), "DLE").single()
    }

    private fun parseEstOtro(): Word {
        val page = File("../testdata/est/otro.html").readText()
        return EstParser.parse(page, httpUrl("https://www.rae.es/diccionario-estudiante/otro"), "EST").single()
    }

    private fun parseCollinsFrente(): Word {
        val page = File("../testdata/colspan/frente.html").readText()
        return CollinsParser.parse(
            page,
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/frente"),
            "COLSPAN", "spanish-english"
        ).first()
    }

    private fun parseCollinsMorir(): Word {
        val page = File("../testdata/colspan/morir.html").readText()
        return CollinsParser.parse(
            page,
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/morir"),
            "COLSPAN", "spanish-english"
        ).first()
    }

    // ---- proposals ----

    @Test
    fun proposals_oneCardPerDefinitionAndIdiom() {
        val word = parseDleOtro()
        val proposals = Cards.proposals(word)

        assertThat(proposals).hasSize(14)
        assertThat(proposals.filterIsInstance<CardProposal.Definition>()).hasSize(7)
        assertThat(proposals.filterIsInstance<CardProposal.Idiom>()).hasSize(7)

        val first = proposals.first()
        assertThat(first).isInstanceOf(CardProposal.Definition::class.java)
        assertThat(first.id).isEqualTo("d0")
        assertThat(first.title).isEqualTo("otro, tra")

        val last = proposals.last()
        assertThat(last.id).isEqualTo("i6")
        assertThat(last).isInstanceOf(CardProposal.Idiom::class.java)
        assertThat((last as CardProposal.Idiom).idiom.idiom).isNotEmpty()
    }

    @Test
    fun proposals_collinsDefinitionsHaveIndexIdsNotTextKeys() {
        val word = parseCollinsFrente()
        val proposals = Cards.proposals(word)

        // Collins definitions carry no definition text (""), so the id must
        // come from the index, never from a text key.
        assertThat(proposals).hasSize(1)
        val def = proposals.single() as CardProposal.Definition
        assertThat(def.definition.definition).isEmpty()
        assertThat(def.id).isEqualTo("d0")
        assertThat(def.title).isEqualTo("frente")
    }

    @Test
    fun proposals_combinedWord_coversEverySelectedDictionary() {
        val dle = parseDleOtro()
        val est = parseEstOtro()
        val entries = MultiDict.entriesFor("DLE", dle) + MultiDict.entriesFor("EST", est)
        val combined = Word.combined(dle, "combined", entries, "otro", null)

        // The combined page stacks every selected dictionary's entries, so the
        // card screen must offer each entry's definitions and idioms (not just
        // the base word's). Definitions first, then idioms, in page order.
        val proposals = Cards.proposals(combined)
        assertThat(proposals).hasSize(dle.definitions.size + est.definitions.size + dle.idioms.size + est.idioms.size)
        assertThat(proposals.filterIsInstance<CardProposal.Definition>())
            .hasSize(dle.definitions.size + est.definitions.size)
        assertThat(proposals.filterIsInstance<CardProposal.Idiom>())
            .hasSize(dle.idioms.size + est.idioms.size)

        val first = proposals.first()
        assertThat(first.id).isEqualTo("d0")
        assertThat(first.title).isEqualTo("otro, tra")

        // Both dictionaries' fragments are reachable: an EST definition's card
        // renders its own element, not the base (DLE) word's.
        val estDef = est.definitions.first()
        val estProposal = proposals.filterIsInstance<CardProposal.Definition>()
            .first { it.definition === estDef }
        assertThat(Cards.definitionBack(combined, listOf(estDef), ""))
            .contains(estDef.element.outerHtml())

        val estProposalIndex = proposals.indexOf(estProposal)
        assertThat(estProposalIndex).isGreaterThan(0)
    }

    // ---- definition Back ----

    @Test
    fun definitionBack_jsonDictionary_rendersFragmentsNotThePage() {
        val word = parseDleOtro()
        val back = Cards.definitionBack(word, listOf(word.definitions[0]), ".x{}")

        assertThat(back).startsWith("<style>.x{}</style>")
        assertThat(back).contains(word.definitions[0].element.outerHtml())
        // DLE splits each word of a definition into its own <span data-id>,
        // so assert on the rendered text, not a contiguous raw substring.
        assertThat(Jsoup.parseBodyFragment(back).text()).contains("Dicho de una persona")
        // A DLE definition is a <li>; the fragment mode renders just that.
        assertThat(word.definitions[0].element.outerHtml()).startsWith("<li")
    }

    @Test
    fun definitionBack_collins_rendersTheHomFragmentNotTheWholePage() {
        val word = parseCollinsFrente()
        val def = word.definitions[0]
        val back = Cards.definitionBack(word, listOf(def), "css{}")

        // Exactly the .hom fragment, wrapped in the extracted CSS.
        assertThat(back).isEqualTo("<style>css{}</style>" + def.element.outerHtml())
        assertThat(def.element.outerHtml()).contains("feminine noun")

        // The page-level chrome stays out of the card back.
        assertThat(word.element.outerHtml()).contains("Log in here")
        assertThat(back).doesNotContain("Log in here")
    }

    @Test
    fun definitionBack_multiDefinition_mergesFragments() {
        val word = parseDleOtro()
        val def1 = word.definitions[0]
        val def2 = word.definitions[1]
        val back = Cards.definitionBack(word, listOf(def1, def2), "css{}")

        assertThat(back).isEqualTo(
            "<style>css{}</style>" + def1.element.outerHtml() + def2.element.outerHtml()
        )
    }

    @Test
    fun definitionBack_combinedWord_doesNotProduceAnEmptyBody() {
        val dle = parseDleOtro()
        val est = parseEstOtro()
        val entries = MultiDict.entriesFor("DLE", dle) + MultiDict.entriesFor("EST", est)
        val combined = Word.combined(dle, "combined", entries, "otro", null)

        // The combined word has no page skeleton (empty element), so the old
        // getPage injection produced an empty <body>. Fragment mode must not.
        val back = Cards.definitionBack(combined, combined.definitions, "css{}")
        assertThat(back).isEqualTo(
            "<style>css{}</style>" + combined.definitions.joinToString("") { it.element.outerHtml() }
        )
        assertThat(back).doesNotContain("<body></body>")
        assertThat(Jsoup.parseBodyFragment(back).text()).contains("Dicho de una persona")
    }

    @Test
    fun definitionBack_combinedWord_canMixDictionaryFragments() {
        val dle = parseDleOtro()
        val est = parseEstOtro()
        val entries = MultiDict.entriesFor("DLE", dle) + MultiDict.entriesFor("EST", est)
        val combined = Word.combined(dle, "combined", entries, "otro", null)

        val mixed = Cards.definitionBack(combined, listOf(dle.definitions[0], est.definitions[0]), "css{}")
        assertThat(mixed).contains(dle.definitions[0].element.outerHtml())
        assertThat(mixed).contains(est.definitions[0].element.outerHtml())
    }

    @Test
    fun definitionBack_legacyDictionary_keepsThePageSkeleton() {
        val doc = Jsoup.parse(
            "<div id='content'><div class='bojning'>fram -en</div>" +
                "<div id='content-betydninger'></div></div>"
        )
        val defEl = Element("div").addClass("definitionIndent")
        val box = defEl.appendElement("div").addClass("definitionBox")
        box.text("a dog")
        val def = Word.Definition("a dog", defEl)
        def.examples.add("The dog barked.")

        val word = Word(
            "DDO", "hund", "hund", "hund", "", httpUrl("https://ordnet.dk/ddo/ordbog?entry_id=1"),
            "https://ordnet.dk/ddo/", doc.selectFirst("#content"), "", null,
            renderAsJson = false
        )
        word.definitions.add(def)

        val back = Cards.definitionBack(word, listOf(def), "legacy{}")
        assertThat(back).startsWith("<style>legacy{}</style>")
        assertThat(back).contains("a dog")
        assertThat(back).contains("fram -en")
    }

    @Test
    fun definitionBack_legacyWithNullCss_keepsHeaderPrefix() {
        val doc = Jsoup.parse("<div id='content'><div id='content-betydninger'></div></div>")
        val defEl = Element("div").text("def")
        val def = Word.Definition("def", defEl)
        val word = Word(
            "DDO", "hund", "hund", "hund", "", httpUrl("https://ordnet.dk/ddo/"),
            "https://ordnet.dk/ddo/", doc.selectFirst("#content"), "<head></head><body>", null,
            renderAsJson = false
        )
        word.definitions.add(def)

        assertThat(Cards.definitionBack(word, listOf(def), null)).contains("def")
    }

    // ---- idiom Back / examples ----

    @Test
    fun idiomBack_buildsStrongPStructure() {
        val idiom = Word.Idiom("frente a", "Enfrente de")
        assertThat(Cards.idiomBack(idiom)).isEqualTo("<strong>frente a</strong><p>Enfrente de")
    }

    @Test
    fun idiomExamples_usesIdiomWhenEmpty() {
        val idiom = Word.Idiom("frente a", "Enfrente de")
        idiom.examples.add("Uso de ejemplo")
        assertThat(Cards.idiomExamples(idiom)).containsExactly("Uso de ejemplo")

        val bare = Word.Idiom("frente a", "Enfrente de")
        assertThat(Cards.idiomExamples(bare)).containsExactly("frente a")
    }

    @Test
    fun examples_legacyUsesDefinitionExamples() {
        val defEl = Element("div").text("def")
        val def = Word.Definition("def", defEl)
        def.examples.add("example 1")

        val word = Word(
            "DDO", "hund", "hund", "hund", "", httpUrl("https://ordnet.dk/ddo/"),
            "https://ordnet.dk/ddo/", Jsoup.parse("<div/>"), "", null, renderAsJson = false
        )

        assertThat(Cards.examples(word, listOf(def), emptyList())).containsExactly("example 1")
    }

    @Test
    fun examples_jsonUsesGlossExamples() {
        // Collins morir: examples live on the gloss, not the definition.
        val word = parseCollinsMorir()
        val def = word.definitions[0]
        assertThat(def.examples).isEmpty()
        assertThat(def.glosses.flatMap { it.examples }).isNotEmpty()

        val examples = Cards.examples(word, listOf(def), emptyList())
        assertThat(examples).hasSize(def.glosses.sumOf { it.examples.size })
        assertThat(examples).containsExactlyElementsIn(def.glosses.flatMap { it.examples })
    }

    @Test
    fun examples_mergesExtrasAndFallsBackToTitle() {
        val defEl = Element("div").text("def")
        val def = Word.Definition("def", defEl)
        def.examples.add("example 1")
        val word = Word(
            "DDO", "hund", "hund", "hund", "", httpUrl("https://ordnet.dk/ddo/"),
            "https://ordnet.dk/ddo/", Jsoup.parse("<div/>"), "", null, renderAsJson = false
        )

        assertThat(Cards.examples(word, listOf(def), listOf("extra"))).containsExactly("example 1", "extra")

        val bare = Word.Definition("def", defEl)
        assertThat(Cards.examples(word, listOf(bare), emptyList())).containsExactly("hund")
    }

    @Test
    fun examples_fallbackPrefersDefinitionTitle() {
        val defEl = Element("div").text("def")
        val titled = Word.Definition("def", defEl, "the title")
        val word = Word(
            "DDO", "hund", "hund", "hund", "", httpUrl("https://ordnet.dk/ddo/"),
            "https://ordnet.dk/ddo/", Jsoup.parse("<div/>"), "", null, renderAsJson = false
        )
        assertThat(Cards.examples(word, listOf(titled), emptyList())).containsExactly("the title")
    }

    // ---- card preview text ----

    @Test
    fun plainText_stripsHtmlFromCollinsExamples() {
        val html = "<span class=\"quote\">ha muerto de repente</span> " +
            "<span class=\"quote\">she died suddenly</span>"
        assertThat(Cards.plainText(html)).isEqualTo("ha muerto de repente she died suddenly")
    }

    @Test
    fun plainText_leavesPlainTextUntouched() {
        assertThat(Cards.plainText("Dicho de una persona")).isEqualTo("Dicho de una persona")
    }

    @Test
    fun definitionText_collinsFallsBackToGloss() {
        val word = parseCollinsFrente()
        val def = word.definitions[0]
        // Collins definitions carry no top-level text; the first gloss's rich
        // HTML becomes the preview line, stripped of markup.
        assertThat(def.definition).isEmpty()
        val text = Cards.definitionText(def)
        assertThat(text).doesNotContain("<")
        assertThat(text).contains("Anatomy")
        assertThat(text).contains("forehead")
    }

    @Test
    fun definitionText_usesTopLevelWhenPresent() {
        val word = parseDleOtro()
        val text = Cards.definitionText(word.definitions[0])
        assertThat(text).isNotEmpty()
        assertThat(text).doesNotContain("<")
    }

    // ---- fields ----

    @Test
    fun fields_ordersAndEncodesAnkiFields() {
        val fields = Cards.fields(
            back = "<b>back</b>",
            examples = listOf("ex one", "ex two"),
            images = listOf("data:image/png;base64,AAA"),
            audio = "a.mp3"
        )

        assertThat(fields).hasLength(4)
        assertThat(fields[0]).isEqualTo("[\"data:image/png;base64,AAA\"]")
        assertThat(fields[1]).isEqualTo("[\"ex one\",\"ex two\"]")
        assertThat(fields[2]).isEqualTo("a.mp3")
        assertThat(fields[3]).isEqualTo("<div style=\"text-align: left\"><b>back</b></div>")
    }
}