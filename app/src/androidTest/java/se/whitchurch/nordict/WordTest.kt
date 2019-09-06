package se.whitchurch.nordict

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.hasToString
import org.hamcrest.core.StringStartsWith.startsWith
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordTest {
    private lateinit var loadResource: IdlingResource

    @get:Rule
    var activityScenarioRule = ActivityScenarioRule<WordActivity>(
            Intent(ApplicationProvider.getApplicationContext(), WordActivity::class.java)
                    .setData(Uri.parse("https://svenska.se/so/?id=18788&ref=lnr176698"))
    )

    @Before
    fun registerIdlingResource() {
        Intents.init()
        activityScenarioRule.scenario.onActivity {
            loadResource = it.loadResource
            IdlingRegistry.getInstance().register(loadResource)
        }
    }

    @After
    fun unregisterIdlingResource() {
        IdlingRegistry.getInstance().unregister(loadResource)
        Intents.release()
    }

    @Test
    fun linkExactMatch() {
        onWebView()
                .withElement(findElement(Locator.XPATH, "//*[text()='lantegendom']"))
                .perform(webClick())
        intended(hasData(hasToString(startsWith("https://svenska.se/so/?sok=lantegendom"))))
    }

    @Test
    fun linkAmbiguous() {
        onWebView()
                .withElement(findElement(Locator.XPATH, "//*[text()='fast']"))
                .perform(webClick())
        onView(withText("fast")).check(matches(isDisplayed()))
    }
}