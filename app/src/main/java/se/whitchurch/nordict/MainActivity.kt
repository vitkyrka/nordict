package se.whitchurch.nordict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import se.whitchurch.nordict.Ordboken.Where
import se.whitchurch.nordict.ui.theme.NordictTheme

/**
 * The unified single-activity home: hosts the navigation graph ([NordictApp])
 * with the global search bar, dictionary nav, history/bookmarks, search
 * results and the word view. Restores the last view on start (word or search
 * query); a fresh install opens the Home (history) screen.
 */
class MainActivity : AppCompatActivity() {
    private var mOrdboken: Ordboken? = null

    /** Set once [NordictApp] composes; read by the debug agent driver. */
    var navController: NavHostController? = null
        private set

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

        val ordboken = Ordboken.getInstance(this)
        mOrdboken = ordboken

        // Initial route from the persisted last view. A fresh install has no
        // "lastWhere" and lands on Home (history + search bar).
        var initialRoute: String? = null
        var initialQuery = ""
        if (ordboken.mPrefs.contains("lastWhere")) {
            when (ordboken.lastWhere) {
                Where.WORD -> ordboken.lastWhat?.let { initialRoute = wordRoute(it, "") }
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
            NordictTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

    /** Navigates to a word view; used by the debug agent driver. */
    fun navigateToWord(uri: Uri, title: String) {
        navController?.navigate(wordRoute(uri.toString(), title))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { data ->
            navController?.navigate(wordRoute(data, intent.getStringExtra("title") ?: ""))
        }
    }

    override fun onResume() {
        super.onResume()
        mOrdboken?.onResume(this)
        mOrdboken?.onDictChanged = null
    }

    override fun onPause() {
        super.onPause()
        mOrdboken?.prefsEditor?.commit()
    }
}