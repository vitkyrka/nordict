package se.whitchurch.nordict

/**
 * Writes Nordict cards into AnkiDroid through an [AnkiApi] adapter: resolves
 * (finding or creating) the deck and the Nordict note model, then adds a
 * note with the fields built by [Cards.fields].
 *
 * Deck/model lookups happen once per [createCard] and mirror the legacy app
 * behavior: scan the existing lists, fall back to creating. The Adapter is
 * pure JVM so the resolution and the field assembly are unit-testable with a
 * mock [AnkiApi].
 */
class AnkiClient(private val api: AnkiApi) {

    /** Adds one note to [deck], returning its new note id, or null on failure. */
    fun createCard(
        deck: String,
        back: String,
        examples: List<String>,
        images: List<String>,
        audio: String
    ): Long? {
        return createFromFields(deck, Cards.fields(back, examples, images, audio))
    }

    /**
     * Adds a note with pre-built fields. The resolution order mirrors the
     * legacy app behavior: a card can only exist when both the deck and the
     * model resolve, so a null id from either fails the whole call.
     */
    fun createFromFields(deck: String, fields: Array<String>): Long? {
        val modelId = getModelId() ?: return null
        val deckId = getDeckId(deck) ?: return null
        return api.addNote(modelId, deckId, fields, null)
    }

    private fun getDeckId(name: String): Long? {
        val deckList = api.deckList()
        if (deckList != null) {
            for (entry in deckList.entries) {
                if (entry.value == name) {
                    return entry.key
                }
            }
        }
        return api.addNewDeck(name)
    }

    private fun getModelId(): Long? {
        val modelList = api.modelList()
        if (modelList != null) {
            for (entry in modelList.entries) {
                if (entry.value == CardModel.NAME) {
                    return entry.key
                }
            }
        }
        return api.addNewCustomModel(
            CardModel.NAME, CardModel.FIELDS, CardModel.CARD_NAMES,
            arrayOf(CardModel.QUESTION_FORMAT), CardModel.AFMT, null, null, null
        )
    }
}