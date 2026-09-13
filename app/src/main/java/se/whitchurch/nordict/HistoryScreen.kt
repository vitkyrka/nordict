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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.whitchurch.nordict.OrdbokenContract.FavoritesEntry
import se.whitchurch.nordict.OrdbokenContract.HistoryEntry

/**
 * The home destination: History/Bookmarks tabs backed by the SQLite tables.
 * Ported from the legacy HistoryActivity, minus the app's search bar (now the
 * global header) and the storage permission request (moved to MainActivity).
 */
@Composable
fun HomeScreen(
    context: Context,
    ordboken: Ordboken,
    onOpenWord: (title: String, url: String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(context.getString(R.string.history)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(context.getString(R.string.bookmarks)) }
            )
        }

        when (selectedTab) {
            1 -> CommonListScreen(
                context = context,
                ordboken = ordboken,
                table = FavoritesEntry.TABLE_NAME,
                titleCol = FavoritesEntry.COLUMN_NAME_TITLE,
                summaryCol = FavoritesEntry.COLUMN_NAME_SUMMARY,
                dictCol = FavoritesEntry.COLUMN_NAME_DICT,
                urlCol = FavoritesEntry.COLUMN_NAME_URL,
                sortOrder = FavoritesEntry.COLUMN_NAME_TITLE + " ASC",
                onOpenWord = onOpenWord
            )

            else -> CommonListScreen(
                context = context,
                ordboken = ordboken,
                table = HistoryEntry.TABLE_NAME,
                titleCol = HistoryEntry.COLUMN_NAME_TITLE,
                summaryCol = HistoryEntry.COLUMN_NAME_SUMMARY,
                dictCol = HistoryEntry.COLUMN_NAME_DICT,
                urlCol = HistoryEntry.COLUMN_NAME_URL,
                sortOrder = HistoryEntry.COLUMN_NAME_DATE + " DESC",
                limit = 100,
                onOpenWord = onOpenWord
            )
        }
    }
}

data class WordRow(
    val id: Long,
    val dict: String,
    val title: String,
    val summary: String,
    val url: String
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
    sortOrder: String,
    limit: Int? = null,
    onOpenWord: (title: String, url: String) -> Unit
) {
    var reloadToken by remember { mutableStateOf(0) }
    val rows by produceState(initialValue = emptyList<WordRow>(), key1 = reloadToken) {
        value = loadRows(context, table, titleCol, summaryCol, dictCol, urlCol, sortOrder, limit)
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
                Text(context.getString(R.string.empty), color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { row ->
                    WordRowItem(
                        context = context,
                        row = row,
                        ordboken = ordboken,
                        onOpen = { onOpenWord(row.title, row.url) },
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
        val flag = ordboken.dictMap[row.dict]?.flag ?: R.drawable.flag_se
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
                    color = Color.Gray,
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

private suspend fun loadRows(
    context: Context,
    table: String,
    titleCol: String,
    summaryCol: String,
    dictCol: String,
    urlCol: String,
    sortOrder: String,
    limit: Int?
): List<WordRow> = withContext(Dispatchers.IO) {
    val dbHelper = OrdbokenDbHelper(context)
    val db = dbHelper.readableDatabase
    try {
        val cursor = db.query(
            table,
            arrayOf(HistoryEntry._ID, dictCol, titleCol, summaryCol, urlCol),
            null, null, null, null,
            sortOrder,
            limit?.toString()
        )
        val rows = mutableListOf<WordRow>()
        while (cursor.moveToNext()) {
            rows.add(
                WordRow(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(HistoryEntry._ID)),
                    dict = cursor.getString(cursor.getColumnIndexOrThrow(dictCol)),
                    title = cursor.getString(cursor.getColumnIndexOrThrow(titleCol)),
                    summary = cursor.getString(cursor.getColumnIndexOrThrow(summaryCol)),
                    url = cursor.getString(cursor.getColumnIndexOrThrow(urlCol))
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