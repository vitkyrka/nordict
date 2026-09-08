package se.whitchurch.nordict

import android.app.SearchManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NeSuggestionProviderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ordboken", Context.MODE_PRIVATE).edit().clear().commit()
        Ordboken.reset()
        Ordboken.getInstance(context, OkHttpClient())
    }

    @After
    fun tearDown() {
        Ordboken.reset()
    }

    @Test
    fun emptyQuerySuggestionUsesTheRawHeadword() {
        val word = Word(
            "DLE", "otro, tra", "otro, tra", "otro, tra",
            "", Uri.parse("https://dle.rae.es/otro"),
            "https://dle.rae.es/", Jsoup.parse("<body></body>").body(),
            "", null, renderAsJson = true
        )
        word.rawHeadword = "otro"
        Ordboken.getInstance(context).currentWord = word

        val provider = Robolectric.buildContentProvider(NeSuggestionProvider::class.java).create().get()
        val uri = Uri.parse(
            "content://se.whitchurch.nordict.NeSuggestionProvider/${SearchManager.SUGGEST_URI_PATH_QUERY}"
        )

        val cursor: Cursor? = provider.query(uri, null, null, null, null)

        assertThat(cursor).isNotNull()
        assertThat(cursor!!.count).isEqualTo(1)
        cursor.moveToFirst()
        assertThat(cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1)))
            .isEqualTo("otro")
    }
}