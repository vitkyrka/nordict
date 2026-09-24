package se.whitchurch.nordict

import com.google.gson.Gson
import org.jsoup.Jsoup

/**
 * One card the user can create for a [Word]: a definition (which may be
 * merged with neighbouring definitions into a single card) or an idiom.
 *
 * `id` is stable within a [Cards.proposals] result so the UI can key
 * per-card images/examples by it. Collins definitions carry no definition
 * text (their content lives in `glosses`), so text content cannot key cards;
 * the index-based ids always can.
 */
sealed interface CardProposal {
    val id: String
    val title: String

    data class Definition(
        override val id: String,
        override val title: String,
        val definition: Word.Definition
    ) : CardProposal

    data class Idiom(
        override val id: String,
        override val title: String,
        val idiom: Word.Idiom
    ) : CardProposal
}

/**
 * Pure card-pipeline logic: enumerating the cards a [Word] proposes, building
 * the synthetic card word each Back renders from, collecting the front
 * examples, and assembling the note fields. The Back itself is rendered by
 * `renderer.js` (`renderCardWord`) inside a hidden WebView and captured as
 * static HTML (see `CardBackRenderer`), so every dictionary — including
 * combined multi-dictionary words, which carry no single page of their own —
 * renders through the same template as the word view.
 */
object Cards {
    private val gson = Gson()

    /** The cards a word's card screen offers: one per definition, then one
     * per idiom, in page order. Collins POS-group definitions carry no
     * top-level text and keep each sense in `glosses`, so those split into
     * one card per gloss (e.g. frente masculine noun offers its 6 senses,
     * not one combined card), like the other dictionaries. Combined
     * multi-dictionary words stack every selected dictionary's entry on one
     * page (`mHomonymEntries` carries the whole set while the word's own
     * `definitions`/`idioms` only hold the first entry), so their proposals
     * flatten every entry, mirroring the page; plain words propose the
     * loaded word's own definitions and idioms. */
    fun proposals(word: Word): List<CardProposal> {
        val entries = word.mHomonymEntries.takeIf {
            it.any { entry -> MultiDict.isCombinedRef(entry.ref) }
        }
        if (entries != null) return combinedProposals(word, entries)

        val out = ArrayList<CardProposal>()
        var d = 0
        for (definition in word.definitions) {
            for (split in splitDefinition(definition)) {
                out.add(CardProposal.Definition("d${d++}", split.title ?: word.mTitle, split))
            }
        }
        word.idioms.forEachIndexed { i, idiom ->
            out.add(CardProposal.Idiom("i$i", word.mTitle, idiom))
        }
        return out
    }

    private fun combinedProposals(word: Word, entries: List<Word.HomonymEntry>): List<CardProposal> {
        val out = ArrayList<CardProposal>()
        var d = 0
        for (entry in entries) {
            for (definition in entry.definitions) {
                for (split in splitDefinition(definition)) {
                    out.add(CardProposal.Definition("d${d++}", split.title ?: entry.mTitle, split))
                }
            }
        }
        var i = 0
        for (entry in entries) {
            for (idiom in entry.idioms) {
                out.add(CardProposal.Idiom("i${i++}", entry.mTitle, idiom))
            }
        }
        return out
    }

    /**
     * Stable identity for hiding a created definition card.
     *
     * `proposals()` mints a fresh [Word.Definition] copy on every call for a
     * split (Collins POS-group) definition, so the copies can never be matched
     * by object identity across recompositions. The single gloss a split copy
     * carries *is* shared (the original gloss instance is moved onto the
     * copy), as are the definition instances of unsplit definitions — keying
     * on `glosses.singleOrNull() ?: definition` therefore identifies the same
     * card on every call. An unsplit single-gloss definition keys on its gloss
     * for the same reason; glosses are never shared between definitions.
     */
    fun hideKey(definition: Word.Definition): Any =
        definition.glosses.singleOrNull() ?: definition

    /** Stable identity for hiding a created idiom card (idioms are never copied). */
    fun hideKey(idiom: Word.Idiom): Any = idiom

    /** The [hideKey] of a card proposal. */
    fun proposalHideKey(proposal: CardProposal): Any = when (proposal) {
        is CardProposal.Definition -> hideKey(proposal.definition)
        is CardProposal.Idiom -> hideKey(proposal.idiom)
    }

    /**
     * The proposals of [word] minus the hidden ones: the entries the card
     * screen still offers. [hidden] holds [hideKey]/[proposalHideKey] keys
     * recorded when their cards were created.
     */
    fun visibleProposals(word: Word, hidden: Set<Any>): List<CardProposal> =
        proposals(word).filterNot { proposalHideKey(it) in hidden }

    /**
     * Splits a Collins POS-group definition (empty top-level text, one gloss
     * per sense) into one single-gloss definition per card. Each copy keeps
     * the group's `pos`/markers and renders its own sense fragment, so its
     * Back, examples and preview cover just that sense. Any other definition
     * (including RAE secondary `.defP` glosses like "También prnl.") stays
     * whole.
     */
    private fun splitDefinition(definition: Word.Definition): List<Word.Definition> {
        if (definition.definition.isNotEmpty() || definition.glosses.size <= 1) {
            return listOf(definition)
        }
        return definition.glosses.map { gloss ->
            val element = gloss.element?.clone() ?: definition.element
            val single = Word.Definition(definition.definition, element, definition.title)
            single.pos = definition.pos
            single.grammar = definition.grammar
            single.gender = definition.gender
            single.domain = definition.domain
            single.geo = definition.geo
            single.plev = definition.plev
            single.register = definition.register
            single.senseNumber = definition.senseNumber.ifEmpty { gloss.senseNumber }
            single.synonyms.addAll(definition.synonyms)
            single.antonyms.addAll(definition.antonyms)
            single.idioms.addAll(definition.idioms)
            single.phrases.addAll(definition.phrases)
            single.examples.addAll(definition.examples)
            single.glosses.add(gloss)
            single
        }
    }

    /**
     * True when [def] comes from this entry: the same definition object
     * (unsplit definitions keep their model objects across [proposals] calls)
     * or a Collins split copy sharing one of the entry's gloss instances.
     */
    private fun Word.HomonymEntry.defines(def: Word.Definition): Boolean =
        definitions.any { candidate ->
            candidate === def ||
                candidate.glosses.any { gloss -> def.glosses.any { it === gloss } }
        }

    /**
     * A synthetic word carrying only the selected definitions and idioms for
     * one card. `renderCardWord` draws it: a single article when the selection
     * comes from one dictionary (the common case — a definition card, a merged
     * same-dictionary card, or an idiom-only card, which renders as a header
     * plus its idiom section), or one `.homonym-entry` section per dictionary
     * when a merge spans dictionaries, so combined entries stay separated
     * under their dictionary labels instead of jammed together.
     *
     * Sections follow the page's dictionary order (`mHomonymEntries`), not the
     * order the merge switches were toggled in; definitions/idioms within one
     * dictionary keep selection order.
     *
     * Definitions/idioms are matched back to their source entry by object
     * identity (idioms are never copied; unsplit definitions keep their model
     * objects) or, for Collins split copies, by their shared gloss instance —
     * the same stable identity [hideKey] keys on. Anything unmatched falls
     * into a trailing group under the word's own header, so the Back is never
     * silently empty.
     */
    fun buildCardWord(word: Word, defs: List<Word.Definition>, idioms: List<Word.Idiom>): Word {
        val title = defs.mapNotNull { it.title }.firstOrNull() ?: word.mTitle
        val card = Word(word.dict, title, word.mSlug, word.summary, word.uri)
        val combined = word.mHomonymEntries.any { MultiDict.isCombinedRef(it.ref) }
        if (!combined) {
            card.dictionary = word.dictionary
            card.gender = word.gender
            card.conjugation = word.conjugation
            card.participle = word.participle
            card.etymology = word.etymology
            card.pronunciation = word.pronunciation
            card.definitions.addAll(defs)
            card.idioms.addAll(idioms)
            return card
        }
        // Partition the selection by source entry, in first-seen order.
        val groups = LinkedHashMap<Word.HomonymEntry?, Pair<ArrayList<Word.Definition>, ArrayList<Word.Idiom>>>()
        for (def in defs) {
            val entry = word.mHomonymEntries.firstOrNull { it.defines(def) }
            groups.getOrPut(entry) { Pair(ArrayList(), ArrayList()) }.first.add(def)
        }
        for (idiom in idioms) {
            val entry = word.mHomonymEntries.firstOrNull { it.idioms.any { candidate -> candidate === idiom } }
            groups.getOrPut(entry) { Pair(ArrayList(), ArrayList()) }.second.add(idiom)
        }
        if (groups.size == 1) {
            val (entry, selection) = groups.entries.single()
            card.dictionary = entry?.dictionary ?: word.dictionary
            card.gender = entry?.gender ?: word.gender
            card.conjugation = entry?.conjugation ?: word.conjugation
            card.participle = entry?.participle ?: word.participle
            card.etymology = entry?.etymology ?: word.etymology
            card.pronunciation = entry?.pronunciation ?: word.pronunciation
            card.definitions.addAll(selection.first)
            card.idioms.addAll(selection.second)
            return card
        }
        card.dictionary = word.dictionary
        // Sections follow the page's dictionary order, not toggle order.
        val pageOrder = word.mHomonymEntries.withIndex().associate { it.value to it.index }
        val ordered = groups.entries.sortedBy { (entry, _) ->
            entry?.let { pageOrder[it] } ?: Int.MAX_VALUE
        }
        for ((entry, selection) in ordered) {
            card.mHomonymEntries.add(
                Word.HomonymEntry(
                    mTitle = selection.first.mapNotNull { it.title }.firstOrNull()
                        ?: entry?.mTitle ?: title,
                    ref = "",
                    dictionary = entry?.dictionary ?: word.dictionary,
                    conjugation = entry?.conjugation ?: "",
                    participle = entry?.participle ?: "",
                    etymology = entry?.etymology ?: "",
                    pronunciation = entry?.pronunciation ?: "",
                    definitions = ArrayList(selection.first),
                    idioms = ArrayList(selection.second),
                    gender = entry?.gender ?: ""
                )
            )
        }
        return card
    }

    /** The example sentences for an idiom card front: the idiom's examples,
     * or the idiom phrase itself so the front is never empty. */
    fun idiomExamples(idiom: Word.Idiom): List<String> {
        return if (idiom.examples.isEmpty()) listOf(idiom.idiom) else idiom.examples.toList()
    }

    /**
     * The example sentences shown on the card front: every chosen
     * definition's examples (from its glosses when it has them — Collins and
     * the RAE dictionaries keep examples there — else the definition-level
     * list), plus any clipboard extras. Falls back to the
     * word's title so the front is never empty.
     */
    fun examples(word: Word, defs: List<Word.Definition>, extras: List<String>): List<String> {
        val out = ArrayList<String>()
        for (def in defs) {
            if (def.glosses.isNotEmpty()) {
                for (gloss in def.glosses) out.addAll(gloss.examples)
            } else {
                out.addAll(def.examples)
            }
        }
        out.addAll(extras)
        if (out.isEmpty()) {
            out.add(defs.mapNotNull { it.title }.firstOrNull() ?: word.mTitle)
        }
        return out
    }

    /**
     * The four note fields, in the Anki model's order: `Images` (a JSON array
     * of data URLs), `Sentences` (a JSON array of HTML), `Audio` (a single URL
     * or data URL) and `Back` (the page HTML, left-aligned like the in-app
     * word view).
     */
    fun fields(back: String, examples: List<String>, images: List<String>, audio: String): Array<String> {
        return arrayOf(
            gson.toJson(images),
            gson.toJson(examples),
            audio,
            "<div style=\"text-align: left\">$back</div>"
        )
    }

    /**
     * Maximum total size (UTF-8 bytes) of the four note fields built by
     * [fields].
     *
     * AnkiDroid itself enforces no per-field size cap (its content provider
     * just inserts whatever `FLDS` it receives), so an oversized card fails
     * one layer down: the `ContentValues` cross process boundaries over
     * Binder, whose transaction buffer is ~1MB shared between all ongoing
     * transactions (`TransactionTooLargeException`, the insert fails and
     * `addNote` returns null). AnkiDroid's own code follows the same rule —
     * `ClipboardUtil` caps pasted text well below half the buffer for exactly
     * this reason — so the note fields stay under half the buffer here too.
     * Images are the only field worth shrinking (base64 data URLs from the
     * camera/picker are PNGs and easily exceed the whole budget alone), which
     * is what [fitFields] does.
     */
    const val MAX_NOTE_FIELDS_BYTES = 500_000

    /** The total UTF-8 byte size of already-built note [fields]. */
    fun fieldsSizeBytes(fields: Array<String>): Int =
        fields.sumOf { it.toByteArray(Charsets.UTF_8).size }

    /**
     * Builds the note fields like [fields], shrinking the `images` data URLs
     * until the total fits in [maxTotalBytes] (default
     * [MAX_NOTE_FIELDS_BYTES]).
     *
     * Each pass downscales the currently largest image through [downscale]
     * (which returns a smaller data URL, or null when the image cannot be
     * made smaller — e.g. it is not a decodable data URL). Images that cannot
     * shrink are kept as long as the rest fits; only when everything
     * shrinkable is exhausted and the fields are still over budget are the
     * largest leftovers dropped, one by one — a card without one image still
     * beats a card that fails to insert entirely. When there is nothing left
     * to shrink or drop, the fields are returned best-effort (oversized).
     */
    fun fitFields(
        back: String,
        examples: List<String>,
        images: List<String>,
        audio: String,
        maxTotalBytes: Int = MAX_NOTE_FIELDS_BYTES,
        downscale: (String) -> String?
    ): Array<String> {
        val settled = ArrayList<String>()
        val shrinkable = images.toMutableList()
        var fields = fields(back, examples, images, audio)
        // Belt and braces: every pass either strictly shrinks the payload or
        // settles/drops an image, so this bound is never reached in practice.
        var passes = (images.size + 1) * 4 + 4
        while (fieldsSizeBytes(fields) > maxTotalBytes && passes-- > 0) {
            val largest = shrinkable.indices.maxByOrNull { shrinkable[it].length }
            if (largest == null) {
                // Nothing shrinkable left: drop the largest settled-or-current
                // image still in the payload, or give up when none remain.
                val current = currentImages(fields)
                val drop = current.indices.maxByOrNull { current[it].length }
                if (drop == null) break
                val remaining = current.toMutableList().also { it.removeAt(drop) }
                fields = fields(back, examples, remaining, audio)
                continue
            }
            val smaller = downscale(shrinkable[largest])
            if (smaller != null && smaller.length < shrinkable[largest].length) {
                shrinkable[largest] = smaller
                fields = fields(back, examples, settled + shrinkable, audio)
            } else {
                // Cannot shrink this one further: keep it for now and try the
                // rest; it becomes droppable once nothing shrinkable remains.
                settled.add(shrinkable.removeAt(largest))
                fields = fields(back, examples, settled + shrinkable, audio)
            }
        }
        return fields
    }

    /**
     * Reads the image data URLs back out of built note [fields] (the `Images`
     * JSON array), or an empty list when the field does not decode. Used by
     * [fitFields] to drop images once nothing can shrink further.
     */
    private fun currentImages(fields: Array<String>): List<String> {
        if (fields.isEmpty()) return emptyList()
        return try {
            gson.fromJson(fields[0], Array<String>::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * A renderable preview of the note Anki would show for a card: the Front
     * fragment (what `CardModel.QUESTION_FORMAT` renders from the `Images` /
     * `Sentences` fields), the exact `Back` note field from [fields], and a
     * standalone page stacking both under Front/Back headings for debugging
     * without opening Anki.
     */
    data class CardPreview(
        val frontHtml: String,
        val backField: String,
        val html: String
    )

    /**
     * The Front fragment for a card preview. Mirrors what the Anki front
     * template renders (the `Images` and `Sentences` fields plus the `Audio`
     * player), but deterministic: every sentence and image is shown, where
     * Anki shows 1-2 random ones. Sentences are emitted as raw HTML exactly
     * like the template's `document.write(s)` does.
     */
    fun previewFront(examples: List<String>, images: List<String>, audio: String): String {
        val out = StringBuilder("<div class=\"preview-front\">")
        for (image in images) {
            out.append("<img src=\"").append(image).append("\"/>")
        }
        for (example in examples) {
            out.append("<p>").append(example).append("</p>")
        }
        if (audio.isNotEmpty()) {
            out.append("<audio controls src=\"").append(audio).append("\"/>")
        }
        out.append("</div>")
        return out.toString()
    }

    /**
     * Builds the preview for a card with note fields
     * `fields(back, examples, images, audio)`: `back` is the captured static
     * card HTML (`CardBackRenderer`: inlined `renderer.css` plus the
     * `renderCardWord` output), `backField` is the exact Anki `Back` field
     * wrapping it left-aligned, and `html` is a standalone page showing the
     * front above the back.
     */
    fun preview(back: String, examples: List<String>, images: List<String>, audio: String): CardPreview {
        val backField = fields(back, examples, images, audio)[3]
        val front = previewFront(examples, images, audio)
        val html = "<html><head><meta name=\"viewport\" content=\"width=device-width\"/>" +
            "</head><body><h2>Front</h2>" + front +
            "<hr/><h2>Back</h2>" + backField + "</body></html>"
        return CardPreview(front, backField, html)
    }

    /**
     * Plain text for the card preview: strips HTML (Collins gloss definitions
     * and examples are rich HTML) while leaving plain text untouched.
     */
    fun plainText(html: String): String = Jsoup.parseBodyFragment(html).text()

    /**
     * The definition line for the card preview. Most definitions carry their
     * text directly; Collins definitions carry none and keep the content in
     * their first gloss, so fall back to it.
     */
    fun definitionText(definition: Word.Definition): String {
        val raw = definition.definition.ifEmpty {
            definition.glosses.firstOrNull()?.definition.orEmpty()
        }
        val text = plainText(raw)
        if (text.isNotEmpty()) return text
        // A sense whose HTML held only its number (e.g. a bare Collins
        // sensenum, now extracted to senseNumber) previews as that number so
        // cards stay distinguishable instead of collapsing to one blank line.
        return definition.senseNumber.ifEmpty {
            definition.glosses.firstOrNull()?.senseNumber.orEmpty()
        }
    }
}