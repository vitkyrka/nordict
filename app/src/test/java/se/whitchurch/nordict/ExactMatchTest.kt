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

    @Test
    fun singularCandidatesStripPluralEndings() {
        // Spanish -s plural, -es plural, -ces -> -z, French -x plural.
        assertThat(ExactMatch.singularCandidates("casas")).containsExactly("casa")
        assertThat(ExactMatch.singularCandidates("flores")).containsExactly("flor", "flore").inOrder()
        assertThat(ExactMatch.singularCandidates("luces")).containsExactly("luz", "luc", "luce").inOrder()
        assertThat(ExactMatch.singularCandidates("tableaux")).containsExactly("tableau")
        // Short article plural still maps ("los" -> "lo"); blank and
        // non-plurals yield nothing.
        assertThat(ExactMatch.singularCandidates("los")).containsExactly("lo")
        assertThat(ExactMatch.singularCandidates("as")).isEmpty()
        assertThat(ExactMatch.singularCandidates("casa")).isEmpty()
        assertThat(ExactMatch.singularCandidates("  ")).isEmpty()
    }

    @Test
    fun fallbackMatchesSingularInSameResults() {
        // A plural search that already suggests its singular resolves without
        // a second network round-trip.
        val results = listOf(
            result("casa", "https://dict.example/casa"),
            result("casamiento", "https://dict.example/casamiento")
        )
        val match = ExactMatch.resolveWithFallback("casas", results)
        assertThat(match).isNotNull()
        assertThat(match!!.mTitle).isEqualTo("casa")
    }

    @Test
    fun fallbackPrefersExactOverSingular() {
        val results = listOf(
            result("casas", "https://dict.example/casas"),
            result("casa", "https://dict.example/casa")
        )
        val match = ExactMatch.resolveWithFallback("casas", results)
        assertThat(match!!.mTitle).isEqualTo("casas")
    }

    @Test
    fun fallbackWithSearchQueriesSingular() {
        // The plural search has no singular; the singular re-search does.
        val pluralResults = listOf(result("casamiento", "https://dict.example/casamiento"))
        val singularResults = listOf(result("casa", "https://dict.example/casa"))
        val match = ExactMatch.resolveWithSearch("casas", pluralResults) { q ->
            assertThat(q).isEqualTo("casa")
            singularResults
        }
        assertThat(match).isNotNull()
        assertThat(match!!.mTitle).isEqualTo("casa")
    }

    @Test
    fun fallbackWithSearchStillNullWithoutSingular() {
        val match = ExactMatch.resolveWithSearch("casas", emptyList()) { emptyList() }
        assertThat(match).isNull()
    }
}
