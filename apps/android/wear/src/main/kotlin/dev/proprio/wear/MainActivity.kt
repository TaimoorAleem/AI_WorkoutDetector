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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as ProprioApp).graph
        val c = graph.controller

        // Runtime permissions: the ongoing recording notification (API 33+) and the Health Services exercise.
        // Everything works if they are denied: the notification is merely hidden / the exercise is skipped.
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
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
