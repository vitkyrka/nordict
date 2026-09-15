package se.whitchurch.nordict

/**
 * The external AnkiDroid content-provider API (see
 * `com.ichi2.anki.api.AddContentApi`). Kept in :core so the card pipeline is
 * testable with a mock; the Android app provides a thin adapter over the real
 * `AddContentApi`.
 *
 * Return values mirror `AddContentApi`: `null` means the operation failed
 * (e.g. AnkiDroid not installed), and deck/model lookups return a map of
 * `id -> name` (or null when the list could not be fetched).
 */
interface AnkiApi {
    fun deckList(): Map<Long, String>?
    fun addNewDeck(name: String): Long?
    fun modelList(): Map<Long, String>?
    fun addNewCustomModel(
        name: String,
        fields: Array<String>,
        cardNames: Array<String>,
        questionFormats: Array<String>,
        answerFormats: Array<String>,
        css: String?,
        did: Long?,
        usn: Int?
    ): Long?
    fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>?): Long?
}