package se.whitchurch.nordict

// Decides whether a dictionary search produced a unique exact match for a
// headword. Used when switching to the same entry in another dictionary of
// the same language: a unique exact match lets the app navigate straight to
// the entry, while no exact match (or several homographs sharing the exact
// title) means the user should pick from the suggestions instead.
object ExactMatch {
    fun resolve(query: String, results: List<SearchResult>): SearchResult? {
        if (results.isEmpty()) return null

        val first = results[0]
        if (first.mTitle != query) return null

        if (results.size == 1 || results[1].mTitle != first.mTitle) return first

        return null
    }
}