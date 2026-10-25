package dev.proprio.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.proprio.mobile.data.ExportBuilder
import dev.proprio.mobile.data.RecordingItem
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.SettingsStore
import dev.proprio.mobile.data.UserSettings
import dev.proprio.mobile.domain.LabelEditor
import dev.proprio.mobile.domain.PlotData
import dev.proprio.mobile.domain.SegmentEdit
import dev.proprio.protocol.Labels
import dev.proprio.protocol.RecordedSession
import dev.proprio.protocol.ResolvedSegment
import dev.proprio.protocol.SegmentKind
import dev.proprio.protocol.SegmentResolver
import dev.proprio.protocol.SessionKind
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val store: SettingsStore) : ViewModel() {
    val settings: StateFlow<UserSettings> = store.settings

    fun saveServerUrl(url: String) = store.update { it.copy(serverUrl = url.trim()) }

    /** Records a new consent timestamp only when the consent value actually changes. */
    fun save(participantId: String, trainingConsent: Boolean, leftWristAcknowledged: Boolean, now: Instant = Instant.now()) {
        store.update {
            it.copy(
                participantId = participantId.trim(),
                trainingConsent = trainingConsent,
                consentAtUtc = if (trainingConsent != it.trainingConsent || it.consentAtUtc == null) now.toString() else it.consentAtUtc,
                leftWristAcknowledged = leftWristAcknowledged,
            )
        }
    }
}

class RecordingsViewModel(store: SessionStore, io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    val items: StateFlow<List<RecordingItem>> = store.changes
        .map { store.list() }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class ReviewUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val sessionId: String = "",
    val complete: Boolean = true,
    val plot: PlotData? = null,
    /** Watch segments with overrides applied plus implicit rest (negative ids), as `SegmentResolver` returns them. */
    val segments: List<ResolvedSegment> = emptyList(),
    val selectedId: Int? = null,
)

class ReviewViewModel(
    private val sessionId: String,
    private val store: SessionStore,
    private val exporter: ExportBuilder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Called after edited labels were saved (the app re-syncs them to the backend). */
    private val onLabelsChanged: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState(sessionId = sessionId))
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var session: RecordedSession? = null
    private var labels: Labels? = null

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(io) { store.load(sessionId) }
                session = loaded
                labels = loaded.labels
                val plot = withContext(io) { PlotData.build(loaded.acc, loaded.gyr) }
                _state.value = ReviewUiState(
                    loading = false,
                    sessionId = sessionId,
                    complete = loaded.meta.complete,
                    plot = plot,
                    segments = loaded.resolvedSegments(),
                )
            } catch (e: Exception) {
                _state.value = ReviewUiState(loading = false, error = "Could not open this recording: ${e.message}")
            }
        }
    }

    fun select(segmentId: Int?) = _state.update { it.copy(selectedId = segmentId) }

    /** Saves edited labels; implicit rest (negative ids) cannot be edited. */
    fun applyEdit(segmentId: Int, edit: SegmentEdit) {
        if (segmentId < 0) return
        mutate { LabelEditor.applyEdit(it, segmentId, edit) }
    }

    /** Moves the segment's start/end by multiples of [LabelEditor.NUDGE_NS]; stored as overrides. */
    fun nudge(segmentId: Int, dStartNs: Long, dEndNs: Long) {
        val mark = session?.segments?.firstOrNull { it.segmentId == segmentId } ?: return
        val defaultKind = if (session?.meta?.kind == SessionKind.CALIBRATION) SegmentKind.CALIBRATION else SegmentKind.SET
        mutate { LabelEditor.nudge(it, mark, defaultKind, dStartNs, dEndNs) }
    }

    private fun mutate(transform: (Labels) -> Labels) {
        val s = session ?: return
        viewModelScope.launch {
            val next = withContext(io) { store.updateLabels(sessionId, transform) }
            labels = next
            _state.update { it.copy(segments = SegmentResolver.resolve(s.meta.kind, s.segments, next)) }
            onLabelsChanged()
        }
    }

    /** Builds the export zip (watch files + labels.json) for the share sheet. */
    suspend fun export(): File = withContext(io) { exporter.build(sessionId) }
}
