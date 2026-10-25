package dev.proprio.backend

import dev.proprio.api.infrastructure.Serializer
import dev.proprio.api.models.Analysis
import dev.proprio.api.models.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalysisTest {
    private val analysisJson = """
        {"sessionId":"$GOLDEN_ID","status":"ANALYZED","model":{"name":"proprio-classifier","version":"3"},
         "segments":[
          {"segmentId":2,"kind":"set","startS":40,"endS":50,"predictedExercise":"squat","predictedKind":"set","confidence":0.75,"repCount":4,"userExercise":"squat","userReps":5,"loadKg":80},
          {"segmentId":1,"kind":"set","startS":10,"endS":20,"predictedExercise":"bench","predictedKind":"set","confidence":0.9,"repCount":5,"userExercise":"squat","userReps":5},
          {"segmentId":3,"kind":"other","startS":25,"endS":30,"predictedKind":"rest"},
          {"segmentId":4,"kind":"rest","startS":30,"endS":40,"predictedKind":"rest"},
          {"segmentId":5,"kind":"rest","startS":50,"endS":60,"predictedKind":"set","predictedExercise":"row","confidence":0.5,"repCount":2}
         ]}
    """.trimIndent()

    private fun parse(json: String) = Serializer.kotlinxSerializationJson.decodeFromString(Analysis.serializer(), json)

    @Test
    fun mapsLabelAgainstPrediction_sortedByTime_andDropsQuietRest() {
        val view = AnalysisView.from(parse(analysisJson))

        assertEquals(SessionStatus.ANALYZED, view.status)
        assertEquals("proprio-classifier 3", view.model)
        assertEquals(listOf(1, 2, 5), view.rows.map { it.segmentId })
        val first = view.rows[0]
        assertEquals("squat", first.userExercise)
        assertEquals("bench", first.predictedExercise)
        assertEquals(false, first.exerciseMatches)
        assertEquals(0, first.repDelta)
        assertEquals(0.9, first.confidence!!, 1e-9)
        val second = view.rows[1]
        assertEquals(true, second.exerciseMatches)
        assertEquals(-1, second.repDelta)
        assertEquals(80.0, second.loadKg!!, 1e-9)
        // A rest segment the model took for a lift stays visible, with nothing to compare against.
        assertNull(view.rows[2].exerciseMatches)
        assertNull(view.rows[2].repDelta)
    }

    @Test
    fun repository_mirrorsRemoteStatusIntoTheLocalState_andReportsFailureCodes() = runBlocking {
        var status = "ANALYZING"
        var failure: String? = null
        ScriptedServer { r ->
            when (r.line()) {
                "GET /v1/sessions/$GOLDEN_ID" -> jsonResponse(200, sessionJson(status, failure))
                "GET /v1/sessions/$GOLDEN_ID/analysis" -> jsonResponse(200, analysisJson)
                else -> status(404)
            }
        }.use { s ->
            val svc = BackendService(MemorySecretStore(), { s.baseUrl }, allowCleartext = true)
            svc.auth.signIn("a@b.c", tokens("acc", "ref"))
            val states = MemoryUploadStateStore()
            val repo = AnalysisRepository({ svc.client() }, states)

            val pending = (repo.refresh(GOLDEN_ID) as AnalysisRepository.Result.Ok).remote
            assertTrue(pending.pending)
            assertNull(pending.analysis)
            assertEquals(UploadPhase.UPLOADED, states.get(GOLDEN_ID)!!.phase)

            status = "ANALYZED"
            val done = (repo.refresh(GOLDEN_ID) as AnalysisRepository.Result.Ok).remote
            assertFalse(done.pending)
            assertEquals(3, done.analysis!!.rows.size)
            assertEquals(UploadPhase.ANALYZED, states.get(GOLDEN_ID)!!.phase)

            status = "FAILED"
            failure = "unsupported_device"
            val failed = (repo.refresh(GOLDEN_ID) as AnalysisRepository.Result.Ok).remote
            assertEquals("unsupported_device", failed.failureReason)
            val st = states.get(GOLDEN_ID)!!
            assertEquals(UploadPhase.FAILED, st.phase)
            assertTrue(st.analysisFailed)
            assertEquals("unsupported_device", st.error)
        }
    }

    @Test
    fun repository_reportsSignedOutAndUnknownSessions() = runBlocking {
        ScriptedServer { status(404) }.use { s ->
            val svc = BackendService(MemorySecretStore(), { s.baseUrl }, allowCleartext = true)
            val repo = AnalysisRepository({ svc.client() }, MemoryUploadStateStore())
            assertEquals(AnalysisRepository.Result.NotSignedIn, repo.refresh(GOLDEN_ID))
            svc.auth.signIn("a@b.c", tokens("acc", "ref"))
            assertEquals(AnalysisRepository.Result.NotFound, repo.refresh(GOLDEN_ID))
        }
    }

    @Test
    fun awaitSettled_pollsWithGrowingDelays_survivesErrors_andStopsWhenAnalyzed() = runBlocking {
        val statuses = ArrayDeque(listOf("UPLOADED", "ERROR", "ANALYZING", "ANALYZED"))
        ScriptedServer { r ->
            when (r.line()) {
                "GET /v1/sessions/$GOLDEN_ID" -> statuses.first().let { if (it == "ERROR") status(503) else jsonResponse(200, sessionJson(it)) }
                "GET /v1/sessions/$GOLDEN_ID/analysis" -> jsonResponse(200, analysisJson)
                else -> status(404)
            }
        }.use { s ->
            val svc = BackendService(MemorySecretStore(), { s.baseUrl }, allowCleartext = true)
            svc.auth.signIn("a@b.c", tokens("acc", "ref"))
            val repo = AnalysisRepository({ svc.client() }, MemoryUploadStateStore())
            val sleeps = mutableListOf<Long>()
            val results = mutableListOf<AnalysisRepository.Result>()

            repo.awaitSettled(GOLDEN_ID, sleep = { sleeps += it; statuses.removeFirst() }) { results += it }

            assertEquals(listOf(2_000L, 3_000L, 4_500L), sleeps)
            assertEquals(4, results.size)
            assertTrue(results[1] is AnalysisRepository.Result.Error)
            assertFalse((results.last() as AnalysisRepository.Result.Ok).remote.pending)
        }
    }

    @Test
    fun backoffIsCappedAtThirtySeconds() {
        assertEquals(2_000L, nextDelayMs(0))
        assertEquals(30_000L, nextDelayMs(20))
    }
}
