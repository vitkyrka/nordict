package se.whitchurch.nordict

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The search-results destination. Runs [Dictionary.fullSearch] for the route
 * query on the current dictionary, with loading/error/empty status panels
 * ported from the legacy MainActivity.
 */
@Composable
fun SearchScreen(
    context: Context,
    ordboken: Ordboken,
    query: String,
    onOpenWord: (SearchResult) -> Unit
) {
    var retries by remember { mutableIntStateOf(0) }
    var results by remember(query, retries) { mutableStateOf<List<SearchResult>?>(null) }
    var isLoading by remember(query, retries) { mutableStateOf(false) }
    var error by remember(query, retries) { mutableStateOf<String?>(null) }

    LaunchedEffect(query, ordboken.currentIndex, retries) {
        if (query.isBlank()) {
            results = emptyList()
            isLoading = false
            error = null
            return@LaunchedEffect
        }
        isLoading = true
        error = null
        val fetched = withContext(Dispatchers.IO) {
            if (!ordboken.isOnline) {
                null
            } else {
                try {
                    ordboken.currentDictionary.fullSearch(query)
                } catch (e: Exception) {
                    null
                }
            }
        }
        isLoading = false
        if (fetched == null) {
            error = if (!ordboken.isOnline) {
                context.getString(R.string.error_offline)
            } else {
                context.getString(R.string.error_results)
            }
        } else {
            results = fetched
        }
    }

    val theError = error
    val theLoading = isLoading
    val theResults = results

    when {
        theError != null -> StatusPanel(
            context = context,
            message = theError,
            onRetry = { retries++ }
        )

        theLoading && theResults == null -> StatusPanel(
            context = context,
            message = context.getString(R.string.loading),
            showProgress = true
        )

        theResults != null && theResults.isEmpty() -> StatusPanel(
            context = context,
            message = context.getString(R.string.no_results)
        )

        else -> {
            val items = theResults.orEmpty()
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
                            .clickable { onOpenWord(searchResult) }
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

@Composable
fun StatusPanel(
    context: Context,
    message: String,
    showProgress: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showProgress) {
            LoadingIndicator(modifier = Modifier.padding(bottom = 16.dp))
        }
        Text(
            text = message,
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Text(context.getString(R.string.tryagain))
            }
        }
    }
}