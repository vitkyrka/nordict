package se.whitchurch.nordict

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {

    private fun launch(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ordboken", Context.MODE_PRIVATE).edit().clear().commit()
        Ordboken.reset()
        return ActivityScenario.launch(MainActivity::class.java)
    }

    private fun dictButton(name: String) = onView(withText(name))

    private fun checkDictButton(name: String) {
        dictButton(name).check(matches(isChecked()))
    }

    @Test
    fun defaultLanguageShowsOnlyItsDictionaries() {
        launch().use {
            onView(withContentDescription("se")).check(matches(isChecked()))
            checkDictButton("SO")
            dictButton("SDO").check(matches(isDisplayed()))
            dictButton("DDO").check(doesNotExist())
            dictButton("DLE").check(doesNotExist())
        }
    }

    @Test
    fun switchingLanguageRebuildsDictionaryRow() {
        launch().use {
            onView(withContentDescription("es")).perform(click())
            onView(withContentDescription("es")).check(matches(isChecked()))

            checkDictButton("DLE")
            dictButton("EST").check(matches(isDisplayed()))
            dictButton("COLSPAN").check(matches(isDisplayed()))

            dictButton("SO").check(doesNotExist())
            dictButton("SDO").check(doesNotExist())
        }
    }

    @Test
    fun everyLanguageMapsToItsDictionaries() {
        launch().use {
            assertOnlyDicts("SO", "SDO")

            onView(withContentDescription("dk")).perform(click())
            assertOnlyDicts("DDO")

            onView(withContentDescription("es")).perform(click())
            assertOnlyDicts("DLE", "EST", "COLSPAN")

            onView(withContentDescription("pt")).perform(click())
            assertOnlyDicts("LINGPT", "INFOPEDIA")

            onView(withContentDescription("fr")).perform(click())
            assertOnlyDicts("WFR", "ROB", "COLFREN")
        }
    }

    @Test
    fun lastUsedDictionaryRememberedPerLanguage() {
        launch().use {
            onView(withContentDescription("es")).perform(click())
            dictButton("COLSPAN").perform(click())
            checkDictButton("COLSPAN")

            onView(withContentDescription("dk")).perform(click())
            checkDictButton("DDO")

            onView(withContentDescription("es")).perform(click())
            checkDictButton("COLSPAN")
        }
    }

    @Test
    fun selectedDictionaryIsRestoredOnRecreate() {
        launch().use { scenario ->
            onView(withContentDescription("es")).perform(click())
            dictButton("COLSPAN").perform(click())
            checkDictButton("COLSPAN")

            scenario.recreate()

            onView(withContentDescription("es")).check(matches(isChecked()))
            checkDictButton("COLSPAN")
        }
    }

    private fun assertOnlyDicts(vararg names: String) {
        for (name in names) {
            dictButton(name).check(matches(isDisplayed()))
        }

        val expected = setOf(*names)
        for (tag in allTags) {
            if (tag !in expected) {
                dictButton(tag).check(doesNotExist())
            }
        }
    }

    private companion object {
        val allTags = arrayOf(
            "SO", "SDO", "DDO", "DLE", "EST", "COLSPAN",
            "DIDAC", "GDLC", "CA-ES", "CA-EN",
            "LINGPT", "INFOPEDIA", "WFR", "ROB", "COLFREN"
        )
    }
}