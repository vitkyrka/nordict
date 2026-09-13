package se.whitchurch.nordict

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val HOME_ROUTE = "home"
const val SEARCH_ROUTE = "search"
const val WORD_ROUTE = "word"

fun wordRoute(uri: String, title: String): String =
    "$WORD_ROUTE?uri=${Uri.encode(uri)}&title=${Uri.encode(title)}"

fun searchRoute(query: String): String =
    "$SEARCH_ROUTE?query=${Uri.encode(query)}"

/**
 * The single-activity app shell: one globally visible MD3 [SearchBar] (with
 * debounced live suggestions from [Ordboken.search]) and the
 * [DictionaryNav] row above a Navigation-Compose [NavHost] with three
 * destinations — home (history), search results, and the word view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NordictApp(
    ordboken: Ordboken,
    initialRoute: String? = null,
    initialQuery: String = "",
    onNavController: (NavHostController) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }
    var searchActive by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<SearchResult>>(emptyList()) }

    fun openWord(uri: Uri, title: String) {
        searchActive = false
        navController.navigate(wordRoute(uri.toString(), title))
    }

    fun openWordUrl(rawUrl: String, title: String) {
        openWord(Uri.parse(rawUrl), title)
    }

    fun runSearch(query: String) {
        searchActive = false
        navController.navigate(searchRoute(query)) { launchSingleTop = true }
    }

    LaunchedEffect(navController) { onNavController(navController) }
    LaunchedEffect(navController, initialRoute) {
        if (initialRoute != null) navController.navigate(initialRoute)
    }

    // Persist the currently displayed destination when the app goes to the
    // background so the next start resumes where the user left off: the word
    // view saves the word it is showing, the search screen saves its query,
    // and home clears the restore point. This mirrors the legacy contract
    // where each activity saved its own screen in its onPause.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, ordboken) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val route = navController.currentDestination?.route
                when {
                    route != null && route.startsWith(WORD_ROUTE) ->
                        ordboken.currentWord?.let {
                            ordboken.setLastView(Ordboken.Where.WORD, it.uri.toString())
                        }
                    route != null && route.startsWith(SEARCH_ROUTE) -> {
                        val query =
                            navController.currentBackStackEntry?.arguments?.getString("query") ?: ""
                        ordboken.setLastView(Ordboken.Where.MAIN, query)
                    }
                    else -> ordboken.setLastView(Ordboken.Where.MAIN, "")
                }
                ordboken.prefsEditor.commit()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Debounced autocomplete suggestions for the global search bar.
    LaunchedEffect(searchQuery, ordboken.currentIndex) {
        if (searchQuery.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = withContext(Dispatchers.IO) { ordboken.search(searchQuery, 50) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Global search header.
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { runSearch(it) },
            active = searchActive,
            onActiveChange = { searchActive = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text(context.getString(R.string.search_hint)) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = context.getString(R.string.menu_search)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = null,
                        modifier = Modifier.clickable { searchQuery = "" }
                    )
                }
            }
        ) {
            if (suggestions.isEmpty()) {
                Text(
                    text = context.getString(R.string.no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                suggestions.forEach { result ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openWord(result.uri.toAndroidUri(), result.mTitle) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = result.mTitle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (result.mSummary.isNotEmpty()) {
                                Text(
                                    text = result.mSummary,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        DictionaryNav(ordboken = ordboken, modifier = Modifier.padding(horizontal = 8.dp))

        HorizontalDivider()

        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = HOME_ROUTE
            ) {
                composable(HOME_ROUTE) {
                    HomeScreen(
                        context = context,
                        ordboken = ordboken,
                        onOpenWord = { title, url -> openWordUrl(url, title) }
                    )
                }
                composable(
                    route = "$SEARCH_ROUTE?query={query}",
                    arguments = listOf(
                        navArgument("query") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { entry ->
                    val query = entry.arguments?.getString("query") ?: ""
                    SearchScreen(
                        context = context,
                        ordboken = ordboken,
                        query = query,
                        onOpenWord = { result -> openWord(result.uri.toAndroidUri(), result.mTitle) }
                    )
                }
                composable(
                    route = "$WORD_ROUTE?uri={uri}&title={title}",
                    arguments = listOf(
                        navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { entry ->
                    WordRoute(
                        entry = entry,
                        ordboken = ordboken,
                        onOpenUri = { uri, title -> openWord(uri, title) },
                        onOpenExternal = { uri ->
                            val browserIntent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(browserIntent)
                        },
                        onFillSearch = { query ->
                            searchQuery = query
                            searchActive = true
                        }
                    )
                }
            }
        }
    }

    // Pressing back while the search overlay is expanded only dismisses the
    // overlay (the legacy search screen in MainActivity did the same); it must
    // not pop the destination underneath it. Registered after the NavHost so
    // it takes precedence over the NavHost's own back handler while enabled.
    BackHandler(enabled = searchActive) { searchActive = false }
}