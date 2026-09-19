package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM tests for [AnkiClient]: deck/model find-or-create resolution and
 * note field assembly against a recording [AnkiApi] mock standing in for the
 * AnkiDroid content provider.
 */
class AnkiClientTest {

    private data class ModelCall(
        val name: String,
        val fields: List<String>,
        val cardNames: List<String>,
        val questionFormats: List<String>,
        val answerFormats: List<String>,
        val css: String?,
        val did: Long?,
        val usn: Int?
    )

    private data class NoteCall(val modelId: Long, val deckId: Long, val fields: List<String>, val tags: Set<String>?)

    private class FakeAnkiApi : AnkiApi {
        var decks = mutableMapOf<Long, String>()
        var models = mutableMapOf<Long, String>()
        var failDeckCreate = false
        var failModelCreate = false
        var failDeckList = false
        var failModelList = false
        var addNoteResult: Long? = 100L

        val createdDecks = mutableListOf<String>()
        val createdModels = mutableListOf<ModelCall>()
        val notes = mutableListOf<NoteCall>()

        override fun deckList(): Map<Long, String>? = if (failDeckList) null else decks
        override fun addNewDeck(name: String): Long? {
            if (failDeckCreate) return null
            createdDecks.add(name)
            val id = decks.size.toLong() + 1
            decks[id] = name
            return id
        }

        override fun modelList(): Map<Long, String>? = if (failModelList) null else models
        override fun addNewCustomModel(
            name: String,
            fields: Array<String>,
            cardNames: Array<String>,
            questionFormats: Array<String>,
            answerFormats: Array<String>,
            css: String?,
            did: Long?,
            usn: Int?
        ): Long? {
            if (failModelCreate) return null
            createdModels.add(
                ModelCall(name, fields.toList(), cardNames.toList(), questionFormats.toList(), answerFormats.toList(), css, did, usn)
            )
            val id = models.size.toLong() + 1
            models[id] = name
            return id
        }

        override fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>?): Long? {
            notes.add(NoteCall(modelId, deckId, fields.toList(), tags))
            return addNoteResult
        }
    }

    @Test
    fun createCard_resolvesDeckAndModel_addsNoteAndFields() {
        val api = FakeAnkiApi()
        val client = AnkiClient(api)

        val id = client.createCard(
            deck = "Nordict - DLE",
            back = "<b>def</b>",
            examples = listOf("ex"),
            images = listOf("img"),
            audio = "a.mp3"
        )

        assertThat(id).isEqualTo(100L)

        // Deck: not present -> created.
        assertThat(api.createdDecks).containsExactly("Nordict - DLE")
        assertThat(api.createdModels).hasSize(1)

        val model = api.createdModels.single()
        assertThat(model.name).isEqualTo(CardModel.NAME)
        assertThat(model.fields).containsExactlyElementsIn(CardModel.FIELDS)
        assertThat(model.cardNames).containsExactlyElementsIn(CardModel.CARD_NAMES)
        assertThat(model.questionFormats).containsExactly(CardModel.QUESTION_FORMAT)
        assertThat(model.answerFormats).containsExactlyElementsIn(CardModel.AFMT)

        assertThat(api.notes).hasSize(1)
        val note = api.notes.single()
        assertThat(note.modelId).isEqualTo(1L)
        assertThat(note.deckId).isEqualTo(1L)
        assertThat(note.fields).hasSize(4)
        assertThat(note.fields[0]).isEqualTo("[\"img\"]")
        assertThat(note.fields[1]).isEqualTo("[\"ex\"]")
        assertThat(note.fields[2]).isEqualTo("a.mp3")
        assertThat(note.fields[3]).isEqualTo("<div style=\"text-align: left\"><b>def</b></div>")
        assertThat(note.tags).isNull()
    }

    @Test
    fun createCard_reusesExistingDeckAndModel() {
        val api = FakeAnkiApi()
        api.decks[42L] = "Nordict - DLE"
        api.models[7L] = CardModel.NAME
        val client = AnkiClient(api)

        val id = client.createCard("Nordict - DLE", "back", emptyList(), emptyList(), "")

        assertThat(id).isEqualTo(100L)
        assertThat(api.createdDecks).isEmpty()
        assertThat(api.createdModels).isEmpty()
        val note = api.notes.single()
        assertThat(note.deckId).isEqualTo(42L)
        assertThat(note.modelId).isEqualTo(7L)
    }

    @Test
    fun createCard_whenAddNoteFails_returnsNull() {
        val api = FakeAnkiApi()
        api.addNoteResult = null
        val client = AnkiClient(api)

        assertThat(client.createCard("Nordict - DLE", "back", emptyList(), emptyList(), "")).isNull()
        assertThat(api.notes).hasSize(1)
    }

    @Test
    fun createCard_whenDeckCreationFails_returnsNull() {
        val api = FakeAnkiApi()
        api.failDeckCreate = true
        val client = AnkiClient(api)

        assertThat(client.createCard("Nordict - DLE", "back", emptyList(), emptyList(), "")).isNull()
        assertThat(api.notes).isEmpty()
    }

    @Test
    fun createCard_whenModelCreationFails_returnsNull() {
        val api = FakeAnkiApi()
        api.failModelCreate = true
        val client = AnkiClient(api)

        assertThat(client.createCard("Nordict - DLE", "back", emptyList(), emptyList(), "")).isNull()
        assertThat(api.notes).isEmpty()
    }

    @Test
    fun createCard_emptyListsEncodeAsEmptyJsonArrays() {
        val api = FakeAnkiApi()
        val client = AnkiClient(api)

        client.createCard("Nordict - DLE", "back", emptyList(), emptyList(), "")

        val note = api.notes.single()
        assertThat(note.fields[0]).isEqualTo("[]")
        assertThat(note.fields[1]).isEqualTo("[]")
        assertThat(note.fields[2]).isEmpty()
    }

    @Test
    fun createCard_shrinksOversizedImagesThroughDownscaler() {
        val api = FakeAnkiApi()
        val client = AnkiClient(api)
        val big = "data:image/png;base64," + "A".repeat(Cards.MAX_NOTE_FIELDS_BYTES)
        var calls = 0

        val id = client.createCard("Nordict - DLE", "back", listOf("ex"), listOf(big), "a.mp3") {
            calls++
            "data:image/jpeg;base64,SMALL"
        }

        assertThat(id).isEqualTo(100L)
        assertThat(calls).isGreaterThan(0)
        val note = api.notes.single()
        assertThat(Cards.fieldsSizeBytes(note.fields.toTypedArray())).isAtMost(Cards.MAX_NOTE_FIELDS_BYTES)
        assertThat(note.fields[0]).contains("SMALL")
        assertThat(note.fields[0]).doesNotContain("AAAA")
    }

    @Test
    fun createCard_withoutDownscaler_dropsUnfittableImagesInsteadOfFailing() {
        val api = FakeAnkiApi()
        val client = AnkiClient(api)
        val big = "data:image/png;base64," + "A".repeat(Cards.MAX_NOTE_FIELDS_BYTES)

        // No downscaler: the image cannot shrink, so it is dropped rather
        // than failing the whole card (the AnkiDroid insert would reject the
        // oversized Binder payload with TransactionTooLargeException).
        val id = client.createCard("Nordict - DLE", "back", listOf("ex"), listOf(big), "a.mp3")

        assertThat(id).isEqualTo(100L)
        val note = api.notes.single()
        assertThat(note.fields[0]).isEqualTo("[]")
        assertThat(note.fields[1]).isEqualTo("[\"ex\"]")
    }
}