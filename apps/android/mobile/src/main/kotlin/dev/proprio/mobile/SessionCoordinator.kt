package dev.proprio.mobile

import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.SettingsStore
import dev.proprio.mobile.data.WatchLink
import dev.proprio.mobile.domain.LabelEditor
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentEvent
import dev.proprio.protocol.SegmentKind
import dev.proprio.protocol.SegmentLabel
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.WatchStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exercise/load/category the user picked for the next set. */
data class SetConfig(
    val exercise: String = "bench",
    val loadKg: Double? = 60.0,
    val category: String = "heavy",
)

data class OpenSegment(val sessionId: String, val segmentId: Int, val kind: MarkKind)

/**
 * Shown after a set stops: asks for the rep count of exactly this segment. When the set was started on the
 * watch ([fromWatch]) the phone's selection at that moment was only a guess, so the prompt also asks the user to
 * confirm the exercise, load and category (prefilled from the label).
 */
data class RepPrompt(
    val sessionId: String,
    val segmentId: Int,
    val exercise: String?,
    val loadKg: Double?,
    val category: String? = null,
    val fromWatch: Boolean = false,
    /** Which kind of segment ended; `other` segments only get a prompt when they were too short. */
    val markKind: MarkKind = MarkKind.SET,
    /** Length in seconds when the segment was shorter than [SHORT_SEGMENT_S] (probably an accidental press). */
    val shortS: Double? = null,
)

/** Segments shorter than this get a "Discard (accidental)" default in the stop prompt. */
const val SHORT_SEGMENT_S = 3.0

/** Convention for a discarded segment (the schema has no such kind): kind `other` + this exact note. */
const val DISCARDED_NOTE = LabelEditor.DISCARDED_NOTE

data class SessionUiState(
    val connected: Boolean = false,
    val watch: WatchStatus? = null,
    val config: SetConfig = SetConfig(),
    val open: OpenSegment? = null,
    val repPrompt: RepPrompt? = null,
    val message: String? = null,
)

/**
 * App-scoped owner of the labeling flow. It lives for the whole process (not a screen) because watch events
 * and session zips arrive whether or not the UI is open.
 *
 * Label binding: the phone never invents segment ids. A label is written when the watch's
 * [SegmentEvent] (STARTED) arrives, keyed by that event's `sessionId` + `segmentId`, straight into the
 * session's `labels.json` on disk. The zip may arrive minutes later (or never, if the session is
 * abandoned): [SessionImporter] only stores the zip next to the already existing labels, so labels
 * that exist before the zip are never lost and never need to be re-attached.
 */
class SessionCoordinator(
    private val scope: CoroutineScope,
    private val link: WatchLink,
    private val store: SessionStore,
    private val importer: SessionImporter,
    private val settings: SettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Called when a session's zip was stored or its labels changed (the app queues a backend upload from here). */
    private val onSessionChanged: (sessionId: String) -> Unit = {},
) {
    /** Phone arrival time of each STARTED event, to measure segment length without a protocol change. */
    private val startedAtMs = HashMap<Pair<String, Int>, Long>()

    private val config = MutableStateFlow(SetConfig())
    private val open = MutableStateFlow<OpenSegment?>(null)
    private val prompt = MutableStateFlow<RepPrompt?>(null)
    private val message = MutableStateFlow<String?>(null)

    /** Config captured when the user pressed start, so edits made before the event arrives don't leak in. */
    @Volatile private var configAtPress: SetConfig? = null

    val state: StateFlow<SessionUiState> = combine(
        link.connected, link.status, config, open, combine(prompt, message) { p, m -> p to m },
    ) { connected, watch, cfg, o, pm ->
        SessionUiState(
            connected, watch, cfg,
            o.takeIf { watch?.state == WatchStatus.State.RECORDING },
            pm.first, pm.second,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SessionUiState())

    init {
        scope.launch { link.segmentEvents.collect { onEvent(it) } }
        scope.launch {
            link.incomingZips.collect { zip ->
                val ack = withContext(io) { importer.import(zip.sessionId, zip.file) }
                link.sendAck(ack)
                if (ack.ok) onSessionChanged(zip.sessionId)
                else message.value = "Received a corrupt session from the watch; it will be retried."
            }
        }
        scope.launch { link.refresh() }
    }

    fun refreshConnection() {
        scope.launch { link.refresh() }
    }

    fun setConfig(transform: (SetConfig) -> SetConfig) = config.update(transform)

    fun dismissMessage() {
        message.value = null
    }

    /**
     * Opens Proprio on the watch first (so the watch may start its foreground service), then sends the normal
     * command as a fallback for an app that is already open (a second start is ignored by the watch). Only when
     * both fail does the user get a message.
     */
    fun startSession(kind: SessionKind) = command("Open Proprio on your watch, then try again.") {
        val participant = settings.settings.value.participantId.ifBlank { null }
        val launched = link.launchWatchApp(kind, participant)
        val commanded = link.startSession(kind, participant)
        launched || commanded
    }

    fun endSession() = command("Could not end the session: watch not reachable.") { link.endSession() }

    fun startSet() {
        configAtPress = config.value
        command("Could not start the set: watch not reachable or no session running.") { link.startSegment(MarkKind.SET) }
    }

    fun startOther() = command("Could not start: watch not reachable or no session running.") {
        link.startSegment(MarkKind.OTHER)
    }

    /** Stops whatever segment is open on the watch (set or other). */
    fun stopSegment() = command("Could not stop: watch not reachable.") { link.stopSegment() }

    /**
     * Saves the rep count (and optional RPE/note) against the segment the prompt was opened for. [confirmed], when
     * given, is the exercise/load/category the user confirmed (watch-started sets) and replaces the label's values.
     */
    fun confirmReps(reps: Int, rpe: Int?, note: String, confirmed: SetConfig? = null) {
        val p = prompt.value ?: return
        scope.launch {
            withContext(io) {
                store.updateLabels(p.sessionId) { labels ->
                    LabelEditor.update(labels, p.segmentId, SegmentKind.SET) {
                        val withReps = it.copy(repsUser = reps, rpe = rpe, note = note)
                        if (confirmed == null) {
                            withReps
                        } else {
                            withReps.copy(exercise = confirmed.exercise, loadKg = confirmed.loadKg, category = confirmed.category)
                        }
                    }
                }
            }
            prompt.value = null
            onSessionChanged(p.sessionId)
        }
    }

    /** Marks the prompt's segment as discarded (accidental press): kind `other`, note [DISCARDED_NOTE], set fields cleared. */
    fun discardSegment() {
        val p = prompt.value ?: return
        scope.launch {
            withContext(io) {
                store.updateLabels(p.sessionId) { labels ->
                    LabelEditor.update(labels, p.segmentId, SegmentKind.OTHER) { LabelEditor.discard(it) }
                }
            }
            prompt.value = null
            onSessionChanged(p.sessionId)
        }
    }

    fun dismissPrompt() {
        prompt.value = null
    }

    private fun command(failure: String, block: suspend () -> Boolean) {
        scope.launch { if (!block()) message.value = failure }
    }

    private suspend fun onEvent(e: SegmentEvent) {
        if (e.source == "auto") return // calibration steps carry no user labels
        when (e.event) {
            SegmentEvent.Event.STARTED -> {
                val kind = e.kind ?: MarkKind.SET
                val label = when (kind) {
                    MarkKind.SET -> {
                        val c = configAtPress ?: config.value
                        SegmentLabel(e.segmentId, SegmentKind.SET, c.exercise, c.loadKg, c.category)
                    }
                    MarkKind.OTHER -> SegmentLabel(e.segmentId, SegmentKind.OTHER)
                }
                configAtPress = null
                withContext(io) { store.updateLabels(e.sessionId) { LabelEditor.put(it, label) } }
                startedAtMs[e.sessionId to e.segmentId] = nowMs()
                open.value = OpenSegment(e.sessionId, e.segmentId, kind)
            }
            SegmentEvent.Event.STOPPED -> {
                val o = open.value
                if (o?.segmentId == e.segmentId && o.sessionId == e.sessionId) open.value = null
                val markKind = e.kind ?: MarkKind.SET
                val lengthS = startedAtMs.remove(e.sessionId to e.segmentId)?.let { (nowMs() - it) / 1000.0 }
                val shortS = lengthS?.takeIf { it < SHORT_SEGMENT_S }
                if (markKind == MarkKind.SET || shortS != null) {
                    val label = withContext(io) { store.readLabels(e.sessionId) }?.let { LabelEditor.find(it, e.segmentId) }
                    prompt.value = RepPrompt(
                        e.sessionId, e.segmentId, label?.exercise, label?.loadKg, label?.category,
                        fromWatch = e.source == "watch", markKind = markKind, shortS = shortS,
                    )
                }
            }
        }
    }
}
