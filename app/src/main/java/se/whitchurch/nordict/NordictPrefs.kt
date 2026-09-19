package se.whitchurch.nordict

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Jetpack DataStore backing the former "ordboken" SharedPreferences. */
val Context.nordictDataStore: DataStore<Preferences> by preferencesDataStore(name = "ordboken")

object NordictPrefs {
    val LAST_WHERE = stringPreferencesKey("lastWhere")
    val LAST_WHAT = stringPreferencesKey("lastWhat")
    val LAST_SOURCES = stringPreferencesKey("lastSources")
    val LAST_REF = stringPreferencesKey("lastRef")
    val CURRENT_INDEX = intPreferencesKey("currentIndex")
    val LAST_LANG = stringPreferencesKey("lastLang")
    val AUTO_PLAY = booleanPreferencesKey("autoPlay")
    val SCALE = intPreferencesKey("scale")

    fun dictIndexKey(lang: String) = intPreferencesKey("dictIndex_$lang")
    fun dictsKey(lang: String) = stringPreferencesKey("dicts_$lang")

    /** Blocking snapshot read for Ordboken's synchronous init/pause paths. */
    fun snapshotBlocking(context: Context): Preferences =
        runBlocking { context.nordictDataStore.data.first() }

    /** Blocking clear for tests (replaces prefs.edit().clear().commit()). */
    fun clearBlocking(context: Context) {
        runBlocking { context.nordictDataStore.edit { it.clear() } }
    }
}
