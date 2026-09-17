package se.whitchurch.nordict

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExactMatchTest {

    private fun result(title: String, uri: String) = SearchResult(title, Uri.parse(uri).toHttpUrl())

    @Test
    fun uniqueExactMatchNavigates() {
        val results = listOf(
            result("persona", "https://dict.example/persona"),
            result("personal", "https://dict.example/personal")
        )
        val match = ExactMatch.resolve("persona", results)
        assertThat(match).isNotNull()
        assertThat(match!!.mTitle).isEqualTo("persona")
    }

    @Test
    fun singleResultExactMatchNavigates() {
        val results = listOf(result("persona", "https://dict.example/persona"))
        val match = ExactMatch.resolve("persona", results)
        assertThat(match).isNotNull()
        assertThat(match!!.uri.toString()).isEqualTo("https://dict.example/persona")
    }

    @Test
    fun noResultsShowsSuggestions() {
        val match = ExactMatch.resolve("persona", emptyList())
        assertThat(match).isNull()
    }

    @Test
    fun firstResultNotTheQueryShowsSuggestions() {
        val results = listOf(result("pesona", "https://dict.example/pesona"))
        assertThat(ExactMatch.resolve("persona", results)).isNull()
    }

    @Test
    fun exactMatchNotFirstNavigates() {
        val results = listOf(
            result("al frente", "https://dict.example/al-frente"),
            result("personal", "https://dict.example/personal"),
            result("persona", "https://dict.example/persona"),
            result("personaje", "https://dict.example/personaje")
        )
        val match = ExactMatch.resolve("persona", results)
        assertThat(match).isNotNull()
        assertThat(match!!.mTitle).isEqualTo("persona")
    }

    @Test
    fun caseInsensitiveExactMatchNavigates() {
        // A merged entry can carry a casing different from the typed query
        // ("Trinidad" from COLSPAN merges with "trinidad" from EST/DLE).
        val results = listOf(
            result("Trinidad", "https://dict.example/trinidad"),
            result("Trinidad y Tobago", "https://dict.example/trinidad-y-tobago")
        )
        val match = ExactMatch.resolve("trinidad", results)
        assertThat(match).isNotNull()
        assertThat(match!!.mTitle).isEqualTo("Trinidad")
    }

    @Test
    fun homographsShowsSuggestions() {
        val results = listOf(
            result("frente", "https://dict.example/frente"),
            result("frente", "https://dict.example/frente/2")
        )
        assertThat(ExactMatch.resolve("frente", results)).isNull()
    }

    @Test
    fun surroundingWhitespaceStillMatches() {
        val results = listOf(result("persona", "https://dict.example/persona"))
        val match = ExactMatch.resolve("  persona  ", results)
        assertThat(match).isNotNull()
        assertThat(match!!.mTitle).isEqualTo("persona")
    }

    @Test
    fun exactMatchWithOtherDifferentResultsNavigates() {
        val results = listOf(
            result("cagar", "https://dict.example/cagar"),
            result("cagar con", "https://dict.example/cagar-con"),
            result("cagarse", "https://dict.example/cagarse")
        )
        val match = ExactMatch.resolve("cagar", results)
        assertThat(match).isNotNull()
        assertThat(match!!.mTitle).isEqualTo("cagar")
    }
}
