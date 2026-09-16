package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DictSelectionTest {

    private val es = listOf("DLE", "EST", "COLSPAN")

    @Test
    fun toggleAppendsNewTagKeepingOrder() {
        assertThat(DictSelection.toggle(es, listOf("DLE", "EST"), "COLSPAN"))
            .containsExactly("DLE", "EST", "COLSPAN").inOrder()
    }

    @Test
    fun toggleRemovesTagKeepingOrder() {
        assertThat(DictSelection.toggle(es, listOf("DLE", "EST", "COLSPAN"), "EST"))
            .containsExactly("DLE", "COLSPAN").inOrder()
    }

    @Test
    fun toggleOffTheLastDictRestoresTheFirstCandidate() {
        // The selection never becomes empty: toggling off the last enabled
        // dict falls back to the first dictionary of the language.
        assertThat(DictSelection.toggle(es, listOf("DLE"), "DLE"))
            .containsExactly("DLE")
        assertThat(DictSelection.toggle(es, listOf("EST"), "EST"))
            .containsExactly("DLE")
    }

    @Test
    fun toggleUnknownTagIsNoop() {
        // A foreign-language or typo'd tag (not a candidate of this language)
        // is ignored.
        val current = listOf("DLE", "COLSPAN")
        assertThat(DictSelection.toggle(es, current, "WFR")).isEqualTo(current)
        assertThat(DictSelection.toggle(es, current, "SO")).isEqualTo(current)
        assertThat(DictSelection.toggle(emptyList(), current, "DLE")).isEqualTo(current)
    }

    @Test
    fun moveShiftsPreservingOtherOrder() {
        val list = listOf("DLE", "EST", "COLSPAN")
        assertThat(DictSelection.move(list, 1, 0))
            .containsExactly("EST", "DLE", "COLSPAN").inOrder()
        assertThat(DictSelection.move(list, 0, 2))
            .containsExactly("EST", "COLSPAN", "DLE").inOrder()
        assertThat(DictSelection.move(list, 2, 0))
            .containsExactly("COLSPAN", "DLE", "EST").inOrder()
    }

    @Test
    fun moveIsBounded() {
        val list = listOf("DLE", "EST", "COLSPAN")
        assertThat(DictSelection.move(list, 0, 0)).isEqualTo(list)
        assertThat(DictSelection.move(list, -1, 1)).isEqualTo(list)
        assertThat(DictSelection.move(list, 1, 3)).isEqualTo(list)
        assertThat(DictSelection.move(list, 3, 0)).isEqualTo(list)
    }
}