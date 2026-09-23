package se.whitchurch.nordict.debug

import android.app.Application

/**
 * The debug-only Application. Registers an [ActivityTracker] and binds the
 * local agent [AgentServer] so a `:cli repl --device <serial>` session can
 * drive the app via `adb forward`. Only present in the debug build (this
 * source set's manifest adds `android:name`); release builds are unaffected.
 */
class DebugApp : Application() {
    var activityTracker: ActivityTracker = ActivityTracker()
        private set
    private var agentServer: AgentServer? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(activityTracker)
        agentServer = AgentServer(this)
        agentServer?.start()
    }
}