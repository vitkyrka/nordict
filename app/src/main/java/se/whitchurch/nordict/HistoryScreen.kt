package se.whitchurch.nordict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.whitchurch.nordict.OrdbokenContract.HistoryEntry

/**
 * History list components backed by the SQLite table. There is no longer a
 * separate home/history destination: the search screen shows this list when
 * its query is empty, and the expanded search sheet shows it in the
 * suggestions area when the search box is empty.
 */
@Composable
fun HistoryList(
    context: Context,
    ordboken: Ordboken,
    onOpenWord: (title: String, url: String, sources: String) -> Unit
) {
    CommonListScreen(
        context = context,
        ordboken = ordboken,
        table = HistoryEntry.TABLE_NAME,
        titleCol = HistoryEntry.COLUMN_NAME_TITLE,
        summaryCol = HistoryEntry.COLUMN_NAME_SUMMARY,
        dictCol = HistoryEntry.COLUMN_NAME_DICT,
        urlCol = HistoryEntry.COLUMN_NAME_URL,
        sourcesCol = HistoryEntry.COLUMN_NAME_SOURCES,
        sortOrder = HistoryEntry.COLUMN_NAME_DATE + " DESC",
        limit = 100,
        onOpenWord = onOpenWord
    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommonListScreen(
    context: Context,
    ordboken: Ordboken,
    table: String,
    titleCol: String,
    summaryCol: String,
    dictCol: String,
    urlCol: String,
    sourcesCol: String? = null,
    sortOrder: String,
    limit: Int? = null,
    onOpenWord: (title: String, url: String, sources: String) -> Unit
) {
    var reloadToken by remember { mutableStateOf(0) }
    val rows by produceState(initialValue = emptyList<WordRow>(), key1 = reloadToken) {
        value = loadRows(context, table, titleCol, summaryCol, dictCol, urlCol, sourcesCol, sortOrder, limit)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                deleteRow(context, table, null)
                reloadToken++
            }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(context.getString(R.string.delete_all))
            }
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
                        context = context,
                        row = row,
                        ordboken = ordboken,
                        onOpen = { onOpenWord(row.title, row.url, row.sources) },
                        onDelete = {
                            deleteRow(context, table, row.url)
                            reloadToken++
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordRowItem(
    context: Context,
    row: WordRow,
    ordboken: Ordboken,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true }
            )
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(context.getString(R.string.delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
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
    var reloadToken by remember { mutableStateOf(0) }
    val rows by produceState(initialValue = emptyList<WordRow>(), key1 = reloadToken) {
        value = loadHistoryRows(context)
    }

    val visible = if (excludeUrl.isNullOrEmpty()) rows else rows.filter { it.url != excludeUrl }
    if (visible.isEmpty()) return false

    Column(modifier = Modifier.fillMaxWidth()) {
        visible.forEach { row ->
            WordRowItem(
                context = context,
                row = row,
                ordboken = ordboken,
                onOpen = { onOpenWord(row.title, row.url, row.sources) },
                onDelete = {
                    deleteHistoryRow(context, row.url)
                    reloadToken++
                }
            )
        }
    }
    return true
}

/** The history table's rows, newest first (shared by the list + suggestions). */
suspend fun loadHistoryRows(context: Context, limit: Int = 100): List<WordRow> =
    loadRows(
        context,
        HistoryEntry.TABLE_NAME,
        HistoryEntry.COLUMN_NAME_TITLE,
        HistoryEntry.COLUMN_NAME_SUMMARY,
        HistoryEntry.COLUMN_NAME_DICT,
        HistoryEntry.COLUMN_NAME_URL,
        HistoryEntry.COLUMN_NAME_SOURCES,
        HistoryEntry.COLUMN_NAME_DATE + " DESC",
        limit
    )

fun deleteHistoryRow(context: Context, url: String?) =
    deleteRow(context, HistoryEntry.TABLE_NAME, url)

private suspend fun loadRows(
    context: Context,
    table: String,
    titleCol: String,
    summaryCol: String,
    dictCol: String,
    urlCol: String,
    sourcesCol: String?,
    sortOrder: String,
    limit: Int?
): List<WordRow> = withContext(Dispatchers.IO) {
    val dbHelper = OrdbokenDbHelper(context)
    val db = dbHelper.readableDatabase
    try {
        val cols = mutableListOf(HistoryEntry._ID, dictCol, titleCol, summaryCol, urlCol)
        if (sourcesCol != null) cols.add(sourcesCol)
        val cursor = try {
            db.query(
                table,
                cols.toTypedArray(),
                null, null, null, null,
                sortOrder,
                limit?.toString()
            )
        } catch (e: Exception) {
            // A pre-migration database without the sources column.
            db.query(
                table,
                arrayOf(HistoryEntry._ID, dictCol, titleCol, summaryCol, urlCol),
                null, null, null, null,
                sortOrder,
                limit?.toString()
            )
        }
        val rows = mutableListOf<WordRow>()
        val sourcesIdx = runCatching { cursor.getColumnIndexOrThrow(sourcesCol) }.getOrNull()
        while (cursor.moveToNext()) {
            rows.add(
                WordRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(HistoryEntry._ID)),
                    dict = cursor.getString(cursor.getColumnIndexOrThrow(dictCol)),
                    title = cursor.getString(cursor.getColumnIndexOrThrow(titleCol)),
                    summary = cursor.getString(cursor.getColumnIndexOrThrow(summaryCol)),
                    url = cursor.getString(cursor.getColumnIndexOrThrow(urlCol)),
                    sources = sourcesIdx?.takeIf { it >= 0 }?.let { cursor.getString(it) } ?: ""
                )
            )
        }
        cursor.close()
        rows
    } finally {
        db.close()
    }
}

private fun deleteRow(context: Context, table: String, url: String?) {
    val dbHelper = OrdbokenDbHelper(context)
    val db: SQLiteDatabase = dbHelper.writableDatabase
    if (url == null) {
        db.delete(table, null, null)
    } else {
        db.delete(table, HistoryEntry.COLUMN_NAME_URL + "=?", arrayOf(url))
    }
    db.close()
}