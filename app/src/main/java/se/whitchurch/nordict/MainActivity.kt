package se.whitchurch.nordict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import se.whitchurch.nordict.Ordboken.Where
import se.whitchurch.nordict.ui.theme.NordictTheme

/**
 * The unified single-activity home: hosts the navigation graph ([NordictApp])
 * with the global search bar, dictionary nav, search
 * results (an empty query shows history) and the word view. Restores the
 * last view on start (word or search query); a fresh install opens the
 * search screen with its history list.
 */
class MainActivity : AppCompatActivity() {
    private var mOrdboken: Ordboken? = null
    private var challengeDialog: AlertDialog? = null

    /** Set once [NordictApp] composes; read by the debug agent driver. */
    var navController: NavHostController? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Application context for the hidden challenge-solving WebView.
        ChallengeWebView.init(this)

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

        val ordboken = Ordboken.getInstance(this)
        mOrdboken = ordboken

        // Initial route from the persisted last view. A fresh install has no
        // persisted state and lands on the search screen (empty query = history).
        var initialRoute: String? = null
        var initialQuery = ""
        if (ordboken.hasPersistedState) {
            when (ordboken.lastWhere) {
                Where.WORD -> ordboken.lastWhat?.let {
                    initialRoute = wordRoute(
                        it, "",
                        sources = ordboken.lastSources,
                        ref = ordboken.lastRef
                    )
                }
                Where.MAIN -> ordboken.lastWhat?.takeIf { it.isNotBlank() }?.let {
                    initialQuery = it
                    initialRoute = searchRoute(it)
                }
                null -> Unit
            }
        }
        // A VIEW/data intent (e.g. tests) routes straight to a word.
        val data = intent.dataString
        if (initialRoute == null && data != null) {
            initialRoute = wordRoute(data, intent.getStringExtra("title") ?: "")
        }

        setContent {
            // The activity implements NavigationEventDispatcherOwner; provide it
            // explicitly so the NavHost's event handler resolves it through the
            // CompositionLocal (the view-tree fallback alone is unreliable under
            // Robolectric).
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this) {
                NordictTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        NordictApp(
                            ordboken = ordboken,
                            initialRoute = initialRoute,
                            initialQuery = initialQuery,
                            onNavController = { navController = it }
                        )
                    }
                }
            }
        }
    }

    /** Navigates to a word view; used by the debug agent driver. */
    fun navigateToWord(uri: Uri, title: String) {
        navController?.navigate(wordRoute(uri.toString(), title))
    }

    /**
     * Runs a full search — the same navigation the search bar's
     * enter action performs, landing on the search-results destination;
     * used by the debug agent driver.
     */
    fun navigateToSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        navController?.navigate(searchRoute(trimmed)) { launchSingleTop = true }
    }

    /**
     * Navigates to a combined multi-dictionary word view; used by the debug
     * agent driver. The word is addressed by its [sources] probe list, so the
     * route stays on the merged page whatever the active dictionary is.
     */
    fun navigateToSources(sources: List<CombSource>, title: String, ref: String?) {
        if (sources.isEmpty()) return
        navController?.navigate(
            wordRoute(
                sources.first().uri.toString(),
                title,
                sources = MultiDict.sourcesToJson(sources),
                ref = ref
            )
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { data ->
            navController?.navigate(wordRoute(data, intent.getStringExtra("title") ?: ""))
        }
    }

    override fun onResume() {
        super.onResume()
        mOrdboken?.onResume()
        mOrdboken?.onDictChanged = null
        // While resumed this activity hosts the human-tap dialog for a
        // Collins challenge the hidden WebView cannot solve on its own.
        ChallengeWebView.tapHost = object : ChallengeWebView.TapHost {
            override fun showChallengeWebView(view: WebView) {
                runOnUiThread { showChallengeDialog(view) }
            }

            override fun hideChallengeWebView() {
                runOnUiThread {
                    challengeDialog?.dismiss()
                    challengeDialog = null
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mOrdboken?.persistBlocking()
        ChallengeWebView.tapHost = null
        challengeDialog?.dismiss()
        challengeDialog = null
    }

    /**
     * Shows the shared hidden challenge WebView visibly so the user can tap
     * through an interactive Cloudflare checkbox. The fetch's poll loop keeps
     * running and dismisses this (via [ChallengeWebView.TapHost]) once the
     * challenge clears.
     */
    private fun showChallengeDialog(view: WebView) {
        if (challengeDialog?.isShowing == true) return
        (view.parent as? ViewGroup)?.removeView(view)
        val height = (420 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    height
                )
            )
        }
        challengeDialog = AlertDialog.Builder(this)
            .setTitle("Dictionary security check")
            .setMessage(
                "Collins asked to verify you are human. " +
                    "Tick the box and this closes itself."
            )
            .setView(container)
            .setCancelable(true)
            .show()
    }
}