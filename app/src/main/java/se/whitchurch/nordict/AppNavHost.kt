package se.whitchurch.nordict

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val HOME_ROUTE = "home"
const val SEARCH_ROUTE = "search"
const val WORD_ROUTE = "word"

fun wordRoute(
    uri: String,
    title: String,
    sources: String? = null,
    ref: String? = null
): String {
    var route = "$WORD_ROUTE?uri=${Uri.encode(uri)}&title=${Uri.encode(title)}"
    if (sources != null) route += "&sources=${Uri.encode(sources)}"
    if (ref != null) route += "&ref=${Uri.encode(ref)}"
    return route
}

fun searchRoute(query: String): String =
    "$SEARCH_ROUTE?query=${Uri.encode(query)}"

/**
 * The single-activity app shell: one globally visible MD3 [SearchBar] (with
 * debounced live suggestions from [Ordboken.search]) and a
 * Navigation-Compose [NavHost] with three destinations — home (history),
 * search results, and the word view. The [DictionaryNav] (language switcher +
 * dictionary chips) lives on the expanded search sheet, above the suggestions.
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

    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarState()
    // The TextFieldState owns the field's text and caret (unlike the legacy
    // String-based SearchBar, whose inferred selection jumped back to the
    // start on a programmatic fill). It is itself saved/restored across a
    // process recreation via its own rememberSaveable saver.
    val textFieldState = rememberTextFieldState(initialText = initialQuery)
    val searchQuery = textFieldState.text.toString()
    var suggestions by remember { mutableStateOf<List<SearchResult>>(emptyList()) }

    fun openWord(uri: Uri, title: String) {
        scope.launch { searchBarState.animateToCollapsed() }
        navController.navigate(wordRoute(uri.toString(), title))
    }

    // Combined words (a search result carrying dictionary provenance) render
    // the merged multi-dict page addressed by a `sources` JSON param; the
    // route also keeps the first source's uri for the back-stack/persistence.
    fun openSources(result: SearchResult) {
        scope.launch { searchBarState.animateToCollapsed() }
        val sources = result.sources
        if (sources.isEmpty()) {
            openWord(result.uri.toAndroidUri(), result.mTitle)
            return
        }
        navController.navigate(
            wordRoute(
                sources.first().uri.toString(),
                result.mTitle,
                sources = MultiDict.sourcesToJson(sources)
            )
        )
    }

    // The selection-reload path: swapping dictionaries on a word view must not
    // stack a redundant pre-switch word destination, so the current word is
    // popped before the reloaded one is pushed.
    fun replaceWord(uri: Uri, title: String) {
        navController.popBackStack()
        openWord(uri, title)
    }

    fun replaceSources(result: SearchResult) {
        navController.popBackStack()
        openSources(result)
    }

    fun openWordUrl(rawUrl: String, title: String) {
        openWord(Uri.parse(rawUrl), title)
    }

    fun runSearch(query: String) {
        scope.launch { searchBarState.animateToCollapsed() }
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

    // Debounced autocomplete suggestions for the global search bar. Recomposes
    // on the selection signature AND the current index so a dictionary or
    // language switch re-runs the prefetch under the new selection — including
    // a single-dict switch, where `activeDicts` (the only state
    // `selectionSignature` reads) never changes.
    LaunchedEffect(searchQuery, ordboken.currentIndex, ordboken.selectionSignature) {
        if (searchQuery.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        suggestions = withContext(Dispatchers.IO) { ordboken.search(searchQuery, 50) }
    }

    // The search field shared by the collapsed bar and the expanded fullscreen
    // sheet, so the caret position set when filling a word sticks in both.
    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { runSearch(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(context.getString(R.string.search_hint)) },
            leadingIcon = {
                // The current language's flag takes over the role of the
                // magnifying glass. currentIndex is the snapshot state every
                // dictionary/language switch writes, so reading it here
                // recomposes the flag after a switch.
                val currentIndex = ordboken.currentIndex
                val currentLang = ordboken.currentLang
                Image(
                    painter = painterResource(ordboken.langFlag(currentLang)),
                    contentDescription = context.getString(R.string.menu_search),
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = context.getString(R.string.search_clear),
                        modifier = Modifier.clickable {
                            textFieldState.setTextAndPlaceCursorAtEnd("")
                            // While the bar is collapsed the X doubles as a tap
                            // on the search bar: clear and open the input
                            // overlay with the caret in place, ready for a new
                            // query. In the open overlay the X stays a plain
                            // text clear.
                            if (searchBarState.currentValue == SearchBarValue.Collapsed) {
                                scope.launch { searchBarState.animateToExpanded() }
                            }
                        }
                    )
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Global search header.
        SearchBar(
            state = searchBarState,
            inputField = inputField
        )

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
                    route = "$WORD_ROUTE?uri={uri}&title={title}&sources={sources}&ref={ref}",
                    arguments = listOf(
                        navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        navArgument("sources") { type = NavType.StringType; defaultValue = "" },
                        navArgument("ref") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { entry ->
                    WordRoute(
                        entry = entry,
                        ordboken = ordboken,
                        onOpenUri = { uri, title -> openWord(uri, title) },
                        onOpenSources = { result -> openSources(result) },
                        onOpenExternal = { uris ->
                            uris.forEach { uri ->
                                val browserIntent = Intent(Intent.ACTION_VIEW, uri)
                                context.startActivity(browserIntent)
                            }
                        },
                        onFillSearch = { query ->
                            textFieldState.setTextAndPlaceCursorAtEnd(query)
                            scope.launch { searchBarState.animateToExpanded() }
                        },
                        onReplaceSources = { result -> replaceSources(result) },
                        onReplaceWord = { uri, title -> replaceWord(uri, title) }
                    )
                }
            }
        }
    }
    }

    // Fullscreen expanded search sheet, drawn as an in-window overlay rather
    // than a dialog: the M3 fullscreen search dialog (1.4 and the 1.5 alphas)
    // swallows the system back button (its DialogWrapper overrides cancel() to
    // a no-op and only dismisses on predictive-back gestures), so KEYCODE_BACK
    // would do nothing while the sheet was open. Inside this activity's own
    // window the BackHandler below receives the key and collapses the sheet
    // without popping the destination underneath it.
    if (searchBarState.currentValue != SearchBarValue.Collapsed) {
        val sheetFieldFocus = remember { FocusRequester() }
        LaunchedEffect(searchBarState.currentValue) {
            if (searchBarState.currentValue != SearchBarValue.Collapsed) {
                sheetFieldFocus.requestFocus()
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = SearchBarDefaults.TonalElevation,
            shadowElevation = SearchBarDefaults.ShadowElevation
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // Single focusable copy of the field lives in the sheet while
                // it is open, so the caret/selection is visible in the window
                // that owns it (the collapsed bar's copy is behind the sheet).
                Box(modifier = Modifier.focusRequester(sheetFieldFocus)) {
                    inputField()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // The dictionary nav (language switcher + dict chips) lives on
                // the search sheet rather than sharing a strip with every
                // screen: it only matters while picking what to search, so it
                // pins above the live suggestions here (and re-renders as the
                // selection changes). The collapsed header holds just the
                // search bar, its leading flag showing the current language.
                DictionaryNav(ordboken = ordboken, modifier = Modifier.padding(horizontal = 8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    val currentWord = ordboken.currentWord
                    if (searchQuery.isBlank() && currentWord != null) {
                        // Artificial first suggestion with an empty search bar: the
                        // current word. The trailing north-west arrow fills the word
                        // into the search field for easy manual editing (as the legacy
                        // SearchView's query-refinement arrow did), placing the caret
                        // at the end so a backspace strips trailing suffixes; tapping
                        // the row itself reopens the word, like any other suggestion.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openWord(currentWord.uri.toAndroidUri(), currentWord.mTitle) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentWord.searchHeadword,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (currentWord.dictionary.isNotEmpty()) {
                                    Text(
                                        text = currentWord.dictionary,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { textFieldState.setTextAndPlaceCursorAtEnd(currentWord.searchHeadword) }
                            ) {
                                Icon(
                                    Icons.Filled.NorthWest,
                                    contentDescription = context.getString(R.string.search_fill_current_word),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (suggestions.isEmpty()) {
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
                                    .clickable { openSources(result) }
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
                                    if (result.dicts.isNotEmpty()) {
                                        Text(
                                            text = result.dicts.joinToString(" · "),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
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
            }
        }
    }

    // Pressing back while the search overlay is expanded only dismisses the
    // overlay (the legacy search screen in MainActivity did the same); it must
    // not pop the destination underneath it. Registered after the NavHost so
    // it takes precedence over the NavHost's own back handler while enabled.
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = searchBarState.currentValue != SearchBarValue.Collapsed) {
        focusManager.clearFocus()
        scope.launch { searchBarState.animateToCollapsed() }
    }
}