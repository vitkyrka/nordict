package se.whitchurch.nordict

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.whitchurch.nordict.OrdbokenContract.FavoritesEntry
import se.whitchurch.nordict.OrdbokenContract.HistoryEntry

class HistoryActivity : AppCompatActivity() {
    private var mOrdboken: Ordboken? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                1
            )
        }

        mOrdboken = Ordboken.getInstance(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryScreen(ordboken = mOrdboken!!)
                }
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

    @Composable
    fun HistoryScreen(ordboken: Ordboken) {
        var selectedTab by remember { mutableStateOf(0) }

        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(getString(R.string.history)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(getString(R.string.bookmarks)) }
                )
            }

            when (selectedTab) {
                1 -> CommonListScreen(
                    ordboken = ordboken,
                    table = FavoritesEntry.TABLE_NAME,
                    titleCol = FavoritesEntry.COLUMN_NAME_TITLE,
                    summaryCol = FavoritesEntry.COLUMN_NAME_SUMMARY,
                    dictCol = FavoritesEntry.COLUMN_NAME_DICT,
                    urlCol = FavoritesEntry.COLUMN_NAME_URL,
                    sortOrder = FavoritesEntry.COLUMN_NAME_TITLE + " ASC"
                )

                else -> CommonListScreen(
                    ordboken = ordboken,
                    table = HistoryEntry.TABLE_NAME,
                    titleCol = HistoryEntry.COLUMN_NAME_TITLE,
                    summaryCol = HistoryEntry.COLUMN_NAME_SUMMARY,
                    dictCol = HistoryEntry.COLUMN_NAME_DICT,
                    urlCol = HistoryEntry.COLUMN_NAME_URL,
                    sortOrder = HistoryEntry.COLUMN_NAME_DATE + " DESC",
                    limit = 100
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun CommonListScreen(
        ordboken: Ordboken,
        table: String,
        titleCol: String,
        summaryCol: String,
        dictCol: String,
        urlCol: String,
        sortOrder: String,
        limit: Int? = null
    ) {
        var reloadToken by remember { mutableStateOf(0) }
        val rows by produceState(initialValue = emptyList<WordRow>(), key1 = reloadToken) {
            value = loadRows(table, titleCol, summaryCol, dictCol, urlCol, sortOrder, limit)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    deleteRow(table, null)
                    reloadToken++
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(getString(R.string.delete_all))
                }
            }

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(getString(R.string.empty), color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { row ->
                        WordRowItem(
                            row = row,
                            ordboken = ordboken,
                            onOpen = {
                                Ordboken.startWordActivity(
                                    this@HistoryActivity,
                                    row.title,
                                    row.url
                                )
                            },
                            onDelete = {
                                deleteRow(table, row.url)
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
                    text = { Text(getString(R.string.delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }

    private suspend fun loadRows(
        table: String,
        titleCol: String,
        summaryCol: String,
        dictCol: String,
        urlCol: String,
        sortOrder: String,
        limit: Int?
    ): List<WordRow> = withContext(Dispatchers.IO) {
        val dbHelper = OrdbokenDbHelper(this@HistoryActivity)
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

    private fun deleteRow(table: String, url: String?) {
        val dbHelper = OrdbokenDbHelper(this)
        val db = dbHelper.writableDatabase
        if (url == null) {
            db.delete(table, null, null)
        } else {
            db.delete(table, HistoryEntry.COLUMN_NAME_URL + "=?", arrayOf(url))
        }
        db.close()
    }

    override fun onPause() {
        super.onPause()

        mOrdboken!!.prefsEditor.commit()
    }

    override fun onResume() {
        super.onResume()

        mOrdboken!!.onResume(this)
    }
}