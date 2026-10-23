package dev.proprio.wear.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SessionKind
import dev.proprio.wear.MainActivity
import dev.proprio.wear.ProprioApp
import dev.proprio.wear.session.Strategy
import kotlinx.coroutines.launch

/**
 * Debug builds only (lives in `src/debug`): drives the same [dev.proprio.wear.session.RecordingController]
 * paths as the UI from `adb shell am broadcast`, so device tests can run without touching the screen.
 *
 * Actions: `dev.proprio.debug.SESSION_START` (extras `strategy`, `keep`, `launch`), `SESSION_END`,
 * `SEGMENT_START` (extra `kind`: set|other), `SEGMENT_STOP`. The broadcast result data is a short outcome string.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as ProprioApp).graph
        val c = graph.controller
        val pending = goAsync()
        graph.scope.launch {
            val result = try {
                when (intent.action) {
                    ACTION_SESSION_START -> {
                        intent.getStringExtra("strategy")?.let { id ->
                            graph.strategy.current = requireNotNull(Strategy.parse(id)) { "unknown strategy '$id'" }
                        }
                        if (intent.hasExtra("keep")) graph.transfer.keepAckedZips = intent.getBooleanExtra("keep", false)
                        if (intent.getBooleanExtra("launch", true)) {
                            // The recording screen must be on top for the "ambient" strategy.
                            context.startActivity(
                                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            )
                        }
                        val ok = c.startSession(SessionKind.GYM, SegmentSource.WATCH)
                        "session start ok=$ok strategy=${graph.strategy.current.id} session=${c.state.value.sessionId}"
                    }
                    ACTION_SESSION_END -> {
                        val id = c.state.value.sessionId
                        val ok = c.endSession(SegmentSource.WATCH)
                        "session end ok=$ok session=$id"
                    }
                    ACTION_SEGMENT_START -> {
                        val kind = if (intent.getStringExtra("kind").equals("other", ignoreCase = true)) MarkKind.OTHER else MarkKind.SET
                        "segment start ok=${c.startSegment(kind, SegmentSource.WATCH)} kind=$kind"
                    }
                    ACTION_SEGMENT_STOP -> "segment stop ok=${c.stopSegment(SegmentSource.WATCH)}"
                    else -> "error: unknown action ${intent.action}"
                }
            } catch (e: Exception) {
                "error: $e"
            }
            Log.i(TAG, "debug broadcast ${intent.action}: $result")
            pending.resultData = result
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "Proprio"
        const val ACTION_SESSION_START = "dev.proprio.debug.SESSION_START"
        const val ACTION_SESSION_END = "dev.proprio.debug.SESSION_END"
        const val ACTION_SEGMENT_START = "dev.proprio.debug.SEGMENT_START"
        const val ACTION_SEGMENT_STOP = "dev.proprio.debug.SEGMENT_STOP"
    }
}
