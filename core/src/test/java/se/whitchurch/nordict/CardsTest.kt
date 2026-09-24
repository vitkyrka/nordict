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
        val page = Goldens.fixtureText("../testdata/dle/otro.html")
        return DleParser.parse(page, httpUrl("https://dle.rae.es/otro"), "DLE").single()
    }

    private fun parseEstOtro(): Word {
        val page = Goldens.fixtureText("../testdata/est/otro.html")
        return EstParser.parse(page, httpUrl("https://www.rae.es/diccionario-estudiante/otro"), "EST").single()
    }

    private fun parseCollinsFrente(): Word {
        val page = Goldens.fixtureText("../testdata/colspan/frente.html")
        return CollinsParser.parse(
            page,
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/frente"),
            "COLSPAN", "spanish-english"
        ).first { it.dictionary == "Collins Spanish-English" }
    }

    private fun parseCollinsMorir(): Word {
        val page = Goldens.fixtureText("../testdata/colspan/morir.html")
        return CollinsParser.parse(
            page,
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/morir"),
            "COLSPAN", "spanish-english"
        ).first { it.dictionary == "Collins Spanish-English" }
    }

    // ---- proposals ----

    @Test
    fun proposals_idsAreUniqueStringsForLazyKeys() {
        // CardActivity keys its LazyColumn by CardProposal.id. The key feeds
        // Compose's saveable-state provider, so it must be Bundle-storable (a
        // String — never a Word.Gloss/Definition/Idiom, which crashes on
        // device) and distinct, or per-card remember state is reused by
        // position and created entries inherit the wrong Merge selection.
        for (word in listOf(parseDleOtro(), parseEstOtro(), parseCollinsMorir())) {
            val ids = Cards.proposals(word).map { it.id }
            assertThat(ids).isNotEmpty()
            assertThat(ids).containsNoDuplicates()
        }
    }

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
        val page = Goldens.fixtureText("../testdata/colspan/frente.html")
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
        val cards = defs.map { Cards.buildCardWord(masc, listOf(it.definition), emptyList()) }
        assertThat(cards.map { it.definitions.single().glosses.single() }.toSet()).hasSize(6)
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
        val page = Goldens.fixtureText("../testdata/est/morir.html")
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

        // Both dictionaries' entries are reachable: an EST definition's card
        // resolves to the EST entry, not the base (DLE) word's.
        val estDef = est.definitions.first()
        val estProposal = proposals.filterIsInstance<CardProposal.Definition>()
            .first { it.definition === estDef }
        val estCard = Cards.buildCardWord(combined, listOf(estDef), emptyList())
        assertThat(estCard.definitions).containsExactly(estDef)
        assertThat(estCard.mHomonymEntries).isEmpty()

        val estProposalIndex = proposals.indexOf(estProposal)
        assertThat(estProposalIndex).isGreaterThan(0)
    }

    // ---- hide-keys (card-view removal) ----

    @Test
    fun hideKey_splitDefinitions_matchAcrossProposalCalls() {
        val page = Goldens.fixtureText("../testdata/colspan/frente.html")
        val masc = CollinsParser.parse(
            page,
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/frente"),
            "COLSPAN", "spanish-english"
        ).single {
            it.dictionary == "Collins Spanish-English" &&
                it.definitions.singleOrNull()?.pos == "masculine noun"
        }

        // Every proposals() call mints fresh Definition copies for a split
        // POS-group definition, so hiding by object identity could never match
        // across recompositions and the created entry stayed visible.
        val first = Cards.proposals(masc).filterIsInstance<CardProposal.Definition>()
        val second = Cards.proposals(masc).filterIsInstance<CardProposal.Definition>()
        assertThat(first.map { it.definition }).isNotEqualTo(second.map { it.definition })
        assertThat(first.map { Cards.proposalHideKey(it) })
            .containsExactlyElementsIn(second.map { Cards.proposalHideKey(it) })

        // Hiding the first entry's key removes exactly that entry (and only
        // it) from a freshly computed proposal list.
        val hidden = setOf(Cards.proposalHideKey(first[0]))
        val visible = Cards.visibleProposals(masc, hidden)
        assertThat(visible).hasSize(first.size - 1)
        assertThat(visible.map { Cards.proposalHideKey(it) }).doesNotContain(Cards.proposalHideKey(first[0]))
        assertThat(visible.map { Cards.proposalHideKey(it) })
            .containsExactlyElementsIn(first.drop(1).map { Cards.proposalHideKey(it) })
    }

    @Test
    fun hideKey_unsplitDefinitionsAndIdioms_areStableAcrossCalls() {
        val word = parseDleOtro()
        val all = Cards.proposals(word)

        // Unsplit definitions keep their model objects across calls (a
        // single-gloss definition keys on its gloss, anything else on itself),
        // so keys match on every call; idioms are never copied.
        val again = Cards.proposals(word)
        assertThat(again.map { Cards.proposalHideKey(it) })
            .containsExactlyElementsIn(all.map { Cards.proposalHideKey(it) })

        val hidden = setOf(Cards.proposalHideKey(all[0]), Cards.proposalHideKey(all.last()))
        val visible = Cards.visibleProposals(word, hidden)
        assertThat(visible).hasSize(all.size - 2)
        assertThat(visible.map { Cards.proposalHideKey(it) })
            .containsExactlyElementsIn(all.drop(1).dropLast(1).map { Cards.proposalHideKey(it) })
    }

    @Test
    fun mergeSelection_byHideKey_survivesFreshProposalCopies() {
        // Regression test for the card-view merge bug: merge state was held
        // in per-card remember + Definition object identity, so scrolling a
        // merged entry out of view (LazyColumn disposal) or recomputing
        // proposals (Collins split copies are fresh objects every call)
        // silently unmerged it. The card view now keys the merge set by
        // stable hide-keys, which must still match after fresh copies.
        val word = parseCollinsMorir()
        val first = Cards.proposals(word).filterIsInstance<CardProposal.Definition>()
        assertThat(first.size).isGreaterThan(1)

        // Select the first entry by hide-key (what toggleMerge stores).
        val selected = mutableMapOf<Any, Word.Definition>()
        selected[Cards.proposalHideKey(first[0])] = first[0].definition

        // Simulate scroll-out + scroll-back: a fresh proposals() call mints
        // new Definition objects, but the stored key must still resolve.
        val again = Cards.proposals(word).filterIsInstance<CardProposal.Definition>()
        assertThat(again.map { it.definition }).isNotEqualTo(first.map { it.definition })
        for (proposal in again) {
            val isMerged = selected.containsKey(Cards.proposalHideKey(proposal))
            assertThat(isMerged).isEqualTo(Cards.proposalHideKey(proposal) == Cards.proposalHideKey(first[0]))
        }

        // Merging a second entry keeps both; deselecting by key clears one.
        selected[Cards.proposalHideKey(again[1])] = again[1].definition
        assertThat(selected).hasSize(2)
        selected.remove(Cards.proposalHideKey(first[0]))
        val third = Cards.proposals(word).filterIsInstance<CardProposal.Definition>()
        assertThat(selected.containsKey(Cards.proposalHideKey(third[0]))).isFalse()
        assertThat(selected.containsKey(Cards.proposalHideKey(third[1]))).isTrue()
    }

    // ---- card word (renderCardWord input) ----

    @Test
    fun buildCardWord_singleDefinition_isASingleArticleWithHeader() {
        val word = parseDleOtro()
        val card = Cards.buildCardWord(word, listOf(word.definitions[0]), emptyList())

        // The card carries the headword title and header for the renderer's
        // <header><h1> block, and renders as one article (no homonym entries).
        assertThat(card.mTitle).isEqualTo("otro, tra")
        assertThat(card.dictionary).isEqualTo(word.dictionary)
        assertThat(card.definitions).containsExactly(word.definitions[0])
        assertThat(card.idioms).isEmpty()
        assertThat(card.mHomonymEntries).isEmpty()
    }

    @Test
    fun buildCardWord_idiomOnly_carriesHeaderAndIdiom() {
        val word = parseDleOtro()
        val idiom = word.idioms.first()
        val card = Cards.buildCardWord(word, emptyList(), listOf(idiom))

        // Idiom-only cards render through the same template path as
        // definitions (header + idiom section), so they get the title and the
        // inlined CSS like every other card.
        assertThat(card.mTitle).isEqualTo(word.mTitle)
        assertThat(card.dictionary).isEqualTo(word.dictionary)
        assertThat(card.definitions).isEmpty()
        assertThat(card.idioms).containsExactly(idiom)
        assertThat(card.mHomonymEntries).isEmpty()
    }

    @Test
    fun buildCardWord_mergedSameDictionary_staysOneArticle() {
        val word = parseDleOtro()
        val defs = listOf(word.definitions[0], word.definitions[1])
        val card = Cards.buildCardWord(word, defs, emptyList())

        assertThat(card.definitions).containsExactlyElementsIn(defs).inOrder()
        assertThat(card.mHomonymEntries).isEmpty()
    }

    @Test
    fun buildCardWord_collinsSplitCopy_matchesItsSourceEntry() {
        val page = Goldens.fixtureText("../testdata/colspan/frente.html")
        val masc = CollinsParser.parse(
            page,
            httpUrl("https://www.collinsdictionary.com/dictionary/spanish-english/frente"),
            "COLSPAN", "spanish-english"
        ).single {
            it.dictionary == "Collins Spanish-English" &&
                it.definitions.singleOrNull()?.pos == "masculine noun"
        }
        // proposals() mints fresh Definition copies sharing the original
        // gloss instances; the card builder must still resolve them.
        val proposals = Cards.proposals(masc).filterIsInstance<CardProposal.Definition>()
        assertThat(proposals).hasSize(6)
        val card = Cards.buildCardWord(
            masc, listOf(proposals[0].definition, proposals[1].definition), emptyList()
        )

        assertThat(card.mTitle).isEqualTo("frente")
        assertThat(card.definitions).hasSize(2)
        assertThat(card.definitions.map { it.glosses.single()?.definition }).containsExactly(
            proposals[0].definition.glosses.single()?.definition,
            proposals[1].definition.glosses.single()?.definition
        )
        assertThat(card.mHomonymEntries).isEmpty()
    }

    @Test
    fun buildCardWord_combinedSameDictionary_staysOneArticle() {
        val dle = parseDleOtro()
        val est = parseEstOtro()
        val entries = MultiDict.entriesFor("DLE", dle) + MultiDict.entriesFor("EST", est)
        val combined = Word.combined(dle, "combined", entries, "otro", null)

        val card = Cards.buildCardWord(
            combined, listOf(dle.definitions[0], dle.definitions[1]), emptyList()
        )

        assertThat(card.definitions).containsExactly(dle.definitions[0], dle.definitions[1])
        assertThat(card.mHomonymEntries).isEmpty()
    }

    @Test
    fun buildCardWord_combinedMergeAcrossDictionaries_groupsPerDictionary() {
        val dle = parseDleOtro()
        val est = parseEstOtro()
        val entries = MultiDict.entriesFor("DLE", dle) + MultiDict.entriesFor("EST", est)
        val combined = Word.combined(dle, "combined", entries, "otro", null)

        // Selected EST-first (reverse toggle order): sections must still
        // follow the page's dictionary order.
        val card = Cards.buildCardWord(
            combined, listOf(est.definitions[0], dle.definitions[0]), emptyList()
        )

        // One section per dictionary, in dictionary order, each carrying only
        // its own definitions under its dictionary label — the renderer draws
        // them as separated .homonym-entry sections instead of jammed text.
        assertThat(card.mHomonymEntries).hasSize(2)
        assertThat(card.mHomonymEntries[0].definitions).containsExactly(dle.definitions[0])
        assertThat(card.mHomonymEntries[1].definitions).containsExactly(est.definitions[0])
        assertThat(card.mHomonymEntries.map { it.dictionary }).containsExactly(
            entries.first { it.definitions.contains(dle.definitions[0]) }.dictionary,
            entries.first { it.definitions.contains(est.definitions[0]) }.dictionary
        )
        assertThat(card.definitions).isEmpty()
    }

    @Test
    fun buildCardWord_combinedIdiom_matchesItsSourceEntry() {
        val dle = parseDleOtro()
        val est = parseEstOtro()
        val entries = MultiDict.entriesFor("DLE", dle) + MultiDict.entriesFor("EST", est)
        val combined = Word.combined(dle, "combined", entries, "otro", null)

        val idiom = est.idioms.first()
        val card = Cards.buildCardWord(combined, emptyList(), listOf(idiom))

        // A single-dictionary selection stays one article headed by that
        // entry, even on a combined word.
        assertThat(card.idioms).containsExactly(idiom)
        assertThat(card.mHomonymEntries).isEmpty()
    }

    // ---- idiom examples ----

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