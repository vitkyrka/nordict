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
 * building (fragment mode, combining for multi-dictionary words) and the note
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
        ).first { it.dictionary == "Collins Spanish-English" }
    }

    private fun parseCollinsMorir(): Word {
        val page = File("../testdata/colspan/morir.html").readText()
        return CollinsParser.parse(
            page,
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/morir"),
            "COLSPAN", "spanish-english"
        ).first { it.dictionary == "Collins Spanish-English" }
    }

    // ---- proposals ----

    @Test
    fun proposals_oneCardPerDefinitionAndIdiom() {
        val word = parseDleOtro()
        val proposals = Cards.proposals(word)

        // DLE otro groups its 7 li idiom senses under 4 h3 headers, so one
        // card per grouped idiom (not per sense).
        assertThat(proposals).hasSize(11)
        assertThat(proposals.filterIsInstance<CardProposal.Definition>()).hasSize(7)
        assertThat(proposals.filterIsInstance<CardProposal.Idiom>()).hasSize(4)

        val first = proposals.first()
        assertThat(first).isInstanceOf(CardProposal.Definition::class.java)
        assertThat(first.id).isEqualTo("d0")
        assertThat(first.title).isEqualTo("otro, tra")

        val last = proposals.last()
        assertThat(last.id).isEqualTo("i3")
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
    fun proposals_collinsMasculineFrente_splitsGlossesIntoSeparateCards() {
        val page = File("../testdata/colspan/frente.html").readText()
        val words = CollinsParser.parse(
            page,
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/frente"),
            "COLSPAN", "spanish-english"
        )
        val masc = words.single {
            it.dictionary == "Collins Spanish-English" &&
                it.definitions.singleOrNull()?.pos == "masculine noun"
        }
        assertThat(masc.definitions).hasSize(1)
        assertThat(masc.definitions[0].glosses).hasSize(6)

        val proposals = Cards.proposals(masc)
        // One card per sense, not one combined card for the POS group.
        assertThat(proposals).hasSize(6)
        val defs = proposals.filterIsInstance<CardProposal.Definition>()
        assertThat(defs).hasSize(6)
        assertThat(defs.map { it.id }).containsExactly("d0", "d1", "d2", "d3", "d4", "d5").inOrder()
        for (proposal in defs) {
            assertThat(proposal.definition.glosses).hasSize(1)
            assertThat(proposal.title).isEqualTo("frente")
        }
        // Each card previews and renders just its own sense.
        assertThat(defs.map { Cards.definitionText(it.definition) }.toSet()).hasSize(6)
        val backs = defs.map { Cards.definitionBack(masc, listOf(it.definition), "") }
        assertThat(backs.toSet()).hasSize(6)
        // The first sense keeps its phrases; other senses keep their own.
        assertThat(defs[0].definition.glosses[0].phrases.map { it.headword }).contains("al frente")
        val firstGlossExamples = defs[0].definition.glosses[0].examples
        val expectedFirst = if (firstGlossExamples.isNotEmpty()) firstGlossExamples else listOf("frente")
        assertThat(Cards.examples(masc, listOf(defs[0].definition), emptyList()))
            .containsExactlyElementsIn(expectedFirst)
    }

    @Test
    fun proposals_multiGlossNonCollinsDefinition_staysWhole() {
        // EST morir def1 carries a secondary "También prnl." gloss; those are
        // one sense with extra grammar, not separate cards.
        val page = File("../testdata/est/morir.html").readText()
        val word = EstParser.parse(
            page, httpUrl("https://www.rae.es/diccionario-estudiante/morir"), "EST"
        ).first { w -> w.definitions.any { it.glosses.size > 1 } }
        val multi = word.definitions.first { it.glosses.size > 1 }
        assertThat(multi.definition).isNotEmpty()

        val proposals = Cards.proposals(word)
        assertThat(proposals.filterIsInstance<CardProposal.Definition>()).hasSize(word.definitions.size)
        val kept = proposals.filterIsInstance<CardProposal.Definition>()
            .single { it.definition === multi }
        assertThat(kept.definition.glosses).hasSize(multi.glosses.size)
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
        val page = File("../testdata/colspan/frente.html").readText()
        assertThat(page).contains("Log in here")
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

        // The combined word has no page skeleton, so fragment mode must render
        // the definitions' elements directly, never an empty body.
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
    fun examples_usesDefinitionExamplesWhenGlossesAreEmpty() {
        val defEl = Element("div").text("def")
        val def = Word.Definition("def", defEl)
        def.examples.add("example 1")

        val word = Word("DDO", "hund", "hund", "hund", httpUrl("https://ordnet.dk/ddo/"))

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
        val word = Word("DDO", "hund", "hund", "hund", httpUrl("https://ordnet.dk/ddo/"))

        assertThat(Cards.examples(word, listOf(def), listOf("extra"))).containsExactly("example 1", "extra")

        val bare = Word.Definition("def", defEl)
        assertThat(Cards.examples(word, listOf(bare), emptyList())).containsExactly("hund")
    }

    @Test
    fun examples_fallbackPrefersDefinitionTitle() {
        val defEl = Element("div").text("def")
        val titled = Word.Definition("def", defEl, "the title")
        val word = Word("DDO", "hund", "hund", "hund", httpUrl("https://ordnet.dk/ddo/"))
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

    // ---- card preview page ----

    @Test
    fun previewFront_rendersEverySentenceImageAndAudioPlayer() {
        val front = Cards.previewFront(
            examples = listOf("ex one", "<b>ex two</b>"),
            images = listOf("data:image/png;base64,AAA"),
            audio = "a.mp3"
        )

        // Sentences render as raw HTML paragraphs (like Anki's document.write),
        // images as <img> and audio with a player control.
        assertThat(front).contains("<p>ex one</p>")
        assertThat(front).contains("<p><b>ex two</b></p>")
        assertThat(front).contains("<img src=\"data:image/png;base64,AAA\"/>")
        assertThat(front).contains("<audio controls src=\"a.mp3\"/>")
    }

    @Test
    fun previewFront_omitsAudioPlayerWhenSilent() {
        val front = Cards.previewFront(listOf("ex"), emptyList(), "")

        assertThat(front).contains("<p>ex</p>")
        assertThat(front).doesNotContain("<audio")
    }

    @Test
    fun preview_stacksFrontAboveTheExactBackField() {
        val back = "<b>back</b>"
        val preview = Cards.preview(back, listOf("ex"), emptyList(), "")

        // The back is the exact Anki Back note field (left-aligned wrapper),
        // so what the preview shows is what Anki would show.
        assertThat(preview.backField).isEqualTo(Cards.fields(back, listOf("ex"), emptyList(), "")[3])
        assertThat(preview.backField).contains(back)
        assertThat(preview.frontHtml).contains("<p>ex</p>")

        val frontHeading = preview.html.indexOf("<h2>Front</h2>")
        val backHeading = preview.html.indexOf("<h2>Back</h2>")
        assertThat(frontHeading).isAtLeast(0)
        assertThat(backHeading).isGreaterThan(frontHeading)
        assertThat(preview.html).contains(preview.frontHtml)
        assertThat(preview.html).contains(preview.backField)
        assertThat(Jsoup.parse(preview.html).text()).contains("ex")
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

    // ---- image fitting (Binder ~1MB budget) ----

    private fun imagesOf(fields: Array<String>): List<String> =
        com.google.gson.Gson().fromJson(fields[0], Array<String>::class.java).toList()

    @Test
    fun fieldsSizeBytes_countsUtf8Bytes() {
        assertThat(Cards.fieldsSizeBytes(arrayOf("a", "é"))).isEqualTo(3)
    }

    @Test
    fun fitFields_underBudget_returnsFieldsUnchangedAndNeverDownscales() {
        var calls = 0
        val images = listOf("data:image/png;base64,AAA")
        val fields = Cards.fitFields("back", listOf("ex"), images, "a.mp3") {
            calls++
            null
        }

        assertThat(fields).isEqualTo(Cards.fields("back", listOf("ex"), images, "a.mp3"))
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun fitFields_overBudget_downscalesLargestImageUntilItFits() {
        val big = "data:image/png;base64," + "A".repeat(2000)
        val small = "data:image/png;base64," + "B".repeat(100)
        var calls = 0
        val fields = Cards.fitFields("b", emptyList(), listOf(big, small), "", maxTotalBytes = 1000) {
            calls++
            if (it.length > 60) it.take(60) else null
        }

        assertThat(calls).isGreaterThan(0)
        assertThat(Cards.fieldsSizeBytes(fields)).isAtMost(1000)
        // Both images survive (shrunk, not dropped); the untouched small one
        // is kept verbatim.
        val kept = imagesOf(fields)
        assertThat(kept).hasSize(2)
        assertThat(kept).contains(small)
        assertThat(kept.single { it.startsWith("data:image/png;base64,A") }.length)
            .isLessThan(big.length)
    }

    @Test
    fun fitFields_overBudgetWithHalvingDownscaler_keepsEveryImage() {
        val images = listOf(
            "data:image/png;base64," + "A".repeat(50_000),
            "data:image/png;base64," + "B".repeat(50_000)
        )
        val fields = Cards.fitFields("b", emptyList(), images, "", maxTotalBytes = 10_000) {
            if (it.length > 30) it.take(it.length / 2) else null
        }

        assertThat(Cards.fieldsSizeBytes(fields)).isAtMost(10_000)
        assertThat(imagesOf(fields)).hasSize(2)
    }

    @Test
    fun fitFields_unshrinkableImages_areDroppedLargestFirstAsLastResort() {
        val big = "data:image/png;base64," + "A".repeat(2000)
        val small = "data:image/png;base64," + "B".repeat(100)
        val fields = Cards.fitFields("b", emptyList(), listOf(big, small), "", maxTotalBytes = 500) {
            null
        }

        // Nothing can shrink, so the largest image is dropped and the small
        // one (which fits) is kept.
        assertThat(imagesOf(fields)).containsExactly(small)
    }

    @Test
    fun fitFields_noImagesOverBudget_returnsBestEffort() {
        val fields = Cards.fitFields("huge back".repeat(100), emptyList(), emptyList(), "", maxTotalBytes = 10) {
            null
        }

        assertThat(fields).isEqualTo(
            Cards.fields("huge back".repeat(100), emptyList(), emptyList(), "")
        )
    }
}