package se.whitchurch.nordict

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Maximum number of recent lookups kept in history. */
const val HISTORY_MAX = 10

/**
 * History list components backed by Jetpack DataStore (a newest-first JSON
 * array under [NordictPrefs.HISTORY], capped at [HISTORY_MAX]). There is no
 * longer a separate home/history destination: the search screen shows this
 * list when its query is empty, and the expanded search sheet shows it in
 * the suggestions area when the search box is empty. Entries cannot be
 * deleted or cleared.
 */
@Composable
fun HistoryList(
    context: Context,
    ordboken: Ordboken,
    onOpenWord: (title: String, url: String, sources: String) -> Unit
) {
    val rows by produceState(initialValue = emptyList<WordRow>()) {
        value = loadHistoryRows(context)
    }

    if (rows.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(context.getString(R.string.empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                WordRowItem(
                    row = row,
                    ordboken = ordboken,
                    onOpen = { onOpenWord(row.title, row.url, row.sources) }
                )
            }
        }
    }
}

data class WordRow(
    val id: Long,
    val dict: String,
    val title: String,
    val summary: String,
    val url: String,
    // A combined multi-dictionary entry's sources probe JSON (empty = single).
    val sources: String = ""
)

@Composable
fun WordRowItem(
    row: WordRow,
    ordboken: Ordboken,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val flag = ordboken.dictMap[row.dict]?.flagCode?.let(::flagResId) ?: R.drawable.flag_se
        Image(
            painter = painterResource(flag),
            contentDescription = null,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (row.summary.isNotEmpty()) {
                Text(
                    text = row.summary,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * History rows for the expanded search sheet's suggestions area (a plain
 * Column, since the sheet already scrolls — a LazyColumn cannot nest in it).
 * [excludeUrl] hides the current word's own row (it already has its
 * artificial suggestion above the history). Returns true when at least one
 * row was shown.
 */
@Composable
fun HistorySuggestionList(
    context: Context,
    ordboken: Ordboken,
    onOpenWord: (title: String, url: String, sources: String) -> Unit,
    excludeUrl: String? = null
): Boolean {
    val rows by produceState(initialValue = emptyList<WordRow>()) {
        value = loadHistoryRows(context)
    }

    val visible = if (excludeUrl.isNullOrEmpty()) rows else rows.filter { it.url != excludeUrl }
    if (visible.isEmpty()) return false

    Column(modifier = Modifier.fillMaxWidth()) {
        visible.forEach { row ->
            WordRowItem(
                row = row,
                ordboken = ordboken,
                onOpen = { onOpenWord(row.title, row.url, row.sources) }
            )
        }
    }
    return true
}

/** The history entries, newest first (shared by the list + suggestions). */
suspend fun loadHistoryRows(context: Context, limit: Int = HISTORY_MAX): List<WordRow> =
    context.nordictDataStore.data
        .map { prefs -> decodeHistory(prefs[NordictPrefs.HISTORY].orEmpty()) }
        .first()
        .take(limit.coerceAtLeast(0))

/**
 * Records a lookup in history: an existing entry for [url] moves to the
 * front, and the list is capped at [HISTORY_MAX].
 */
suspend fun saveHistoryEntry(
    context: Context,
    dict: String,
    title: String,
    summary: String,
    url: String,
    sources: String = ""
) {
    context.nordictDataStore.edit { prefs ->
        val rows = decodeHistory(prefs[NordictPrefs.HISTORY].orEmpty())
            .filterNot { it.url == url }
            .toMutableList()
        rows.add(
            0,
            WordRow(
                id = url.hashCode().toLong(),
                dict = dict,
                title = title,
                summary = summary,
                url = url,
                sources = sources
            )
        )
        prefs[NordictPrefs.HISTORY] = encodeHistory(rows.take(HISTORY_MAX))
    }
}

private fun decodeHistory(raw: String): List<WordRow> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            val url = obj.optString("url")
            WordRow(
                id = url.hashCode().toLong(),
                dict = obj.optString("dict"),
                title = obj.optString("title"),
                summary = obj.optString("summary"),
                url = url,
                sources = obj.optString("sources")
            )
        }
    }.getOrDefault(emptyList())
}

private fun encodeHistory(rows: List<WordRow>): String =
    JSONArray().apply {
        rows.forEach { row ->
            put(
                JSONObject().apply {
                    put("dict", row.dict)
                    put("title", row.title)
                    put("summary", row.summary)
                    put("url", row.url)
                    put("sources", row.sources)
                }
            )
        }
    }.toString()
