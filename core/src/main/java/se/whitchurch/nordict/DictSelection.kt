package se.whitchurch.nordict

/**
 * Pure selection/reorder math for the multi-dictionary selection, kept in
 * `:core` (no Android) so the semantics are unit-testable on a desktop JVM.
 * The app's [Ordboken] turns these ordered tag lists into dictionaries,
 * persists them, and fires its change hook; the UI only ever hands over
 * tags.
 */
object DictSelection {

    /**
     * The ordered selection after toggling [tag] among the language's
     * combining [candidates] (in registration order). Toggling on appends
     * (keeps the existing relative order); toggling off removes. The
     * selection never becomes empty: toggling off the last selected dict
     * restores the first candidate. An unknown [tag] (not combining, a
     * different language, or a typo) is a no-op.
     */
    fun toggle(
        candidates: List<String>,
        current: List<String>,
        tag: String
    ): List<String> {
        if (tag !in candidates) return current
        return if (tag in current) {
            val rest = current.filterNot { it == tag }
            if (rest.isEmpty()) listOf(candidates.first()) else rest
        } else {
            current + tag
        }
    }

    /**
     * Moves the tag at [from] to [to] (both 0-based indices into [current])
     * while preserving the untagged items' relative order. Bounded: an
     * out-of-range or equal index leaves the list unchanged.
     */
    fun move(current: List<String>, from: Int, to: Int): List<String> {
        if (from !in current.indices || to !in current.indices || from == to) {
            return current
        }
        val mutable = current.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        return mutable
    }
}