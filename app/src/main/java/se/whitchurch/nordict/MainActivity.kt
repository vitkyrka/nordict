package se.whitchurch.nordict

import android.app.SearchManager
import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import se.whitchurch.nordict.Ordboken.Where

class MainActivity : AppCompatActivity() {
    private var mOrdboken: Ordboken? = null
    private var mLastQuery: String? = null
    private var mSeenResults: Boolean = false

    private val queryText = mutableStateOf("")
    private val resultsList = mutableStateOf<List<SearchResult>?>(null)
    private val isLoading = mutableStateOf(false)
    private val errorMessage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mOrdboken = Ordboken.getInstance(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }

        val intent = intent
        if (Intent.ACTION_SEARCH == intent.action || Intent.ACTION_VIEW == intent.action) {
            onNewIntent(intent)
        } else {
            restoreLastView()
        }
    }

    @Composable
    fun MainScreen() {
        val ordboken = mOrdboken!!
        val results = resultsList.value
        val loading = isLoading.value
        val error = errorMessage.value

        Column(modifier = Modifier.fillMaxSize()) {
            // Search bar
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(ordboken.currentFlag),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = queryText.value,
                        onValueChange = { queryText.value = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Search") },
                        trailingIcon = {
                            if (queryText.value.isNotEmpty()) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    modifier = Modifier
                                        .clickable { queryText.value = "" }
                                )
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { doSearch(queryText.value) }
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { doSearch(queryText.value) }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            }

            // Language/dictionary navigation rows
            DictionaryNav(ordboken = ordboken, modifier = Modifier.padding(horizontal = 8.dp))

            HorizontalDivider()

            when {
                error != null -> {
                    StatusPanel(
                        message = error,
                        onRetry = { doSearchAgain() }
                    )
                }

                loading && results == null -> {
                    StatusPanel(
                        message = getString(R.string.loading),
                        showProgress = true
                    )
                }

                results != null && results.isEmpty() -> {
                    StatusPanel(
                        message = getString(R.string.no_results),
                        showProgress = false
                    )
                }

                else -> {
                    val items = results.orEmpty()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(items, key = { it.uri.toString() }) { searchResult ->
                            Text(
                                text = searchResult.mTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        Ordboken.startWordActivity(
                                            this@MainActivity,
                                            searchResult.mTitle,
                                            searchResult.uri.toAndroidUri()
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                            if (searchResult.mSummary.isNotEmpty()) {
                                Text(
                                    text = searchResult.mSummary,
                                    fontSize = 14.sp,
                                    color = Color.Gray,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StatusPanel(
        message: String,
        showProgress: Boolean = false,
        onRetry: (() -> Unit)? = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            if (showProgress) {
                LoadingIndicator(modifier = Modifier.padding(bottom = 16.dp))
            }
            Text(
                text = message,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text(getString(R.string.tryagain))
                }
            }
        }
    }

    private fun restoreLastView() {
        val where = mOrdboken!!.lastWhere
        val what = mOrdboken!!.lastWhat

        if (where == Where.MAIN) {
            doSearch(what)
        } else if (where == Where.WORD) {
            Ordboken.startWordActivity(this, "", what!!)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()

        mOrdboken!!.setLastView(Where.MAIN, mLastQuery ?: "")
        mOrdboken!!.prefsEditor.commit()
    }

    override fun onResume() {
        super.onResume()

        mOrdboken!!.onResume(this)
        mOrdboken!!.onDictChanged = null
    }

    private inner class SearchTask : AsyncTask<String, Void, Array<SearchResult>?>() {
        override fun doInBackground(vararg params: String): Array<SearchResult>? {
            if (!mOrdboken!!.isOnline) {
                return null
            }

            return try {
                mOrdboken!!.currentDictionary.fullSearch(params[0]).toTypedArray()
            } catch (e: Exception) {
                null
            }
        }

        override fun onPostExecute(results: Array<SearchResult>?) {
            isLoading.value = false

            if (results == null) {
                errorMessage.value =
                    if (!mOrdboken!!.isOnline) getString(R.string.error_offline)
                    else getString(R.string.error_results)
                return
            }

            mSeenResults = true
            resultsList.value = results.toList()
            errorMessage.value = null
        }
    }

    private fun doSearch(query: String?) {
        query?.let { queryText.value = it }
        mLastQuery = query
        isLoading.value = true
        errorMessage.value = null
        SearchTask().execute(query)
    }

    private fun doSearchAgain() {
        doSearch(mLastQuery)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (Intent.ACTION_SEARCH == intent.action) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            doSearch(query)
        } else if (Intent.ACTION_VIEW == intent.action) {
            val url = intent.dataString
            val word = intent.getStringExtra(SearchManager.EXTRA_DATA_KEY)
            Ordboken.startWordActivity(this, word!!, url!!)

            if (!mSeenResults) {
                finish()
            }
        }
    }
}