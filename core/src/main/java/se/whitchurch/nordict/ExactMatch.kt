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

        val trimmed = query.trim()
        val exact = results.filter { it.mTitle.equals(trimmed, ignoreCase = true) }
        return exact.singleOrNull()
    }

    /**
     * Singular candidates for a `/search/` link query that had no exact match.
     * word.js links every word inside definitions/examples, including inflected
     * forms (e.g. a Spanish plural like "casas"); the dictionaries only index
     * lemmas ("casa"). Generic plural-stripping order (most specific first):
     * `-ces` -> `-z` (luz/luces), `-es` -> `` (flor/flores), `-s` -> ``
     * (casa/casas), `-x` -> `` (tableau/tableaux). Case-insensitive; the
     * stripped form keeps the query's casing (matching itself is
     * case-insensitive anyway). Only forms of length >= 2 are suggested.
     */
    fun singularCandidates(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < 3) return emptyList()
        val lower = trimmed.lowercase()
        val out = ArrayList<String>(3)
        if (lower.endsWith("ces") && trimmed.length - 3 + 1 >= 2) {
            out.add(trimmed.dropLast(3) + "z")
        }
        if (lower.endsWith("es") && trimmed.length - 2 >= 2) {
            out.add(trimmed.dropLast(2))
        }
        if (lower.endsWith("s") && trimmed.length - 1 >= 2) {
            out.add(trimmed.dropLast(1))
        }
        // French -eau plurals (tableau/tableaux); harmless elsewhere.
        if (lower.endsWith("x") && trimmed.length - 1 >= 2) {
            out.add(trimmed.dropLast(1))
        }
        return out.distinct().filter { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }
    }

    /**
     * [resolve] plus the singular fallback against an already-fetched result
     * list: when [query] has no exact match, each [singularCandidates] form is
     * tried against the same [results] (a plural search often already suggests
     * its singular). Null when nothing matches uniquely.
     */
    fun resolveWithFallback(query: String, results: List<SearchResult>): SearchResult? {
        resolve(query, results)?.let { return it }
        for (candidate in singularCandidates(query)) {
            resolve(candidate, results)?.let { return it }
        }
        return null
    }

    /**
     * [resolveWithFallback] plus a fresh search per singular candidate: when
     * neither [query] nor its singulars match [firstResults], [search] is
     * called once per candidate (in [singularCandidates] order) and the first
     * unique exact match wins. Callers that can re-query (the word view's
     * `/search/` handler, the agent `open` op) should use this; pure list
     * merging uses [resolveWithFallback].
     */
    fun resolveWithSearch(
        query: String,
        firstResults: List<SearchResult>,
        search: (String) -> List<SearchResult>
    ): SearchResult? {
        resolveWithFallback(query, firstResults)?.let { return it }
        for (candidate in singularCandidates(query)) {
            val results = try {
                search(candidate)
            } catch (e: Exception) {
                continue
            }
            resolve(candidate, results)?.let { return it }
        }
        return null
    }
}