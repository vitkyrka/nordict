package se.whitchurch.nordict

// Decides whether a dictionary search produced a unique exact match for a
// headword. Used when switching to the same entry in another dictionary of
// the same language: a unique exact match lets the app navigate straight to
// the entry, while no exact match (or several homographs sharing the exact
// title) means the user should pick from the suggestions instead.
//
// The match is looked up case-insensitively and anywhere in the results: the
// combined suggestion list is sorted alphabetically ignoring case, so a headword
// that a user typed exactly need not sit at position 0 anymore (and a merged
// entry can carry a casing that differs from the query). Titles that fold to the
// query more than once (homographs) stay ambiguous.
object ExactMatch {
    fun resolve(query: String, results: List<SearchResult>): SearchResult? {
        if (results.isEmpty()) return null

        val exact = results.filter { it.mTitle.equals(query, ignoreCase = true) }
        return exact.singleOrNull()
    }
}