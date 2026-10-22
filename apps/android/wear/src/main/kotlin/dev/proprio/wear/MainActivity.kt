package dev.proprio.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.wear.compose.material3.MaterialTheme
import dev.proprio.wear.ui.WatchScreen
import androidx.compose.runtime.collectAsState
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SessionKind
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The ongoing recording notification needs POST_NOTIFICATIONS on API 33+. The health FGS type
        // itself needs no runtime permission here (HIGH_SAMPLING_RATE_SENSORS is declared in the manifest).
        if (Build.VERSION.SDK_INT >= 33) {
            // The FGS runs either way; the notification is merely hidden if denied.
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val graph = (application as ProprioApp).graph
        val c = graph.controller
        setContent {
            MaterialTheme {
                val state by c.state.collectAsState()
                WatchScreen(
                    state = state,
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
