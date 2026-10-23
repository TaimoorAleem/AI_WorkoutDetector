package dev.proprio.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.MaterialTheme
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SessionKind
import dev.proprio.wear.ui.WatchScreen
import dev.proprio.wear.platform.appLog
import dev.proprio.wear.session.StartRequestHandler
import dev.proprio.wear.session.keepScreenOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var ambient by mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambient = true
        }

        override fun onExitAmbient() {
            ambient = false
        }
    }

    private fun applyScreenOn(on: Boolean) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (on) window.addFlags(flag) else window.clearFlags(flag)
        // No brightness override for now: at 0.01 the screen was barely readable (battery tuning is pre-launch work)
        appLog("screen_on ${if (on) "held" else "released"}")
    }

    /** Start request from the phone's remote launch; consumed once, run when the activity is visible. */
    private var pendingStartUri: String? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingStartUri = intent.dataString
        runPendingStart()
    }

    override fun onResume() {
        super.onResume()
        runPendingStart()
    }

    private fun runPendingStart() {
        val uri = pendingStartUri ?: return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        pendingStartUri = null
        val graph = (application as ProprioApp).graph
        lifecycleScope.launch { StartRequestHandler(graph.controller, ::appLog).handle(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as ProprioApp).graph
        val c = graph.controller
        pendingStartUri = if (savedInstanceState == null) intent?.dataString else null

        // Runtime permissions: the ongoing recording notification (API 33+) and the Health Services exercise.
        // Everything works if they are denied: the notification is merely hidden / the exercise is skipped.
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            add(Manifest.permission.BODY_SENSORS)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 1)
        }

        // Ambient support (strategy "ambient"): with the observer registered the system keeps this activity on
        // top in ambient mode when the screen dims, instead of sending the app to the background (where Samsung
        // freezes it). It is fixed at creation, so a strategy change that flips it recreates the activity.
        // FLAG_KEEP_SCREEN_ON is deliberately not used.
        if (graph.strategy.current.ambient) {
            lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        }
        lifecycleScope.launch {
            graph.strategy.changes.map { it.ambient }.distinctUntilChanged().drop(1).collect { recreate() }
        }

        // Strategy "screen_on": while recording keep this activity on screen (TOP processes are not frozen),
        // at minimum brightness; both are reset when the session ends or the strategy changes.
        lifecycleScope.launch {
            combine(graph.strategy.changes, c.state.map { it.recording }.distinctUntilChanged(), ::keepScreenOn)
                .distinctUntilChanged()
                .collect { on -> applyScreenOn(on) }
        }

        setContent {
            MaterialTheme {
                val state by c.state.collectAsState()
                WatchScreen(
                    state = state,
                    ambient = ambient,
                    onStartSession = { graph.scope.launch { c.startSession(SessionKind.GYM, SegmentSource.WATCH) } },
                    onEndSession = { graph.scope.launch { c.endSession(SegmentSource.WATCH) } },
                    onStartSet = { graph.scope.launch { c.startSegment(MarkKind.SET, SegmentSource.WATCH) } },
                    onStartOther = { graph.scope.launch { c.startSegment(MarkKind.OTHER, SegmentSource.WATCH) } },
                    onStopSegment = { graph.scope.launch { c.stopSegment(SegmentSource.WATCH) } },
                    onStartCalibration = { graph.scope.launch { c.startSession(SessionKind.CALIBRATION, SegmentSource.WATCH) } },
                    onRetryTransfers = { graph.scope.launch { c.resumeTransfers() } },
                )
            }
        }
    }
}
