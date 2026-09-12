package se.whitchurch.nordict.debug

import android.app.Activity
import android.app.Application
import java.util.Collections

/**
 * Tracks the topmost resumed [Activity] (a LIFO stack) so the agent driver
 * knows what the user/agent is looking at without holding a reference to a
 * specific one.
 *
 * A simple "last resumed" pointer is not enough: when a finished [Activity]
 * is destroyed, its destroy callback runs *after* the activity below has
 * resumed, so clearing on destroy would wrongly drop the current activity to
 * null. With a stack, the activity below naturally becomes the current one.
 */
class ActivityTracker : Application.ActivityLifecycleCallbacks {
    private val stack = Collections.synchronizedList(ArrayList<Activity>())

    val current: Activity?
        get() = stack.firstOrNull()

    override fun onActivityResumed(activity: Activity) {
        stack.remove(activity)
        stack.add(0, activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        stack.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
}