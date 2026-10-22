package dev.proprio.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Validates generated JSON against the JSON Schemas in protocol/schema with networknt json-schema-validator. */
class SchemaValidationTest {
    @Test
    fun completeAndIncompleteMetaValidate() {
        assertEquals(emptyList<String>(), TestSupport.errors(TestSupport.metaSchema, TestSupport.sampleMeta(true).toJson()))
        assertEquals(emptyList<String>(), TestSupport.errors(TestSupport.metaSchema, TestSupport.sampleMeta(false).toJson()))
        val cal = TestSupport.sampleMeta(true, SessionKind.CALIBRATION).toJson()
        assertEquals(emptyList<String>(), TestSupport.errors(TestSupport.metaSchema, cal))
    }

    @Test
    fun incompleteMetaOmitsEndedFields() {
        val tree = TestSupport.tree(TestSupport.sampleMeta(false).toJson())
        assertFalse(tree.has("clock_anchor_end"))
        assertFalse(tree.has("ended_at_utc"))
        assertFalse(tree.get("complete").asBoolean())
    }

    @Test
    fun completeMetaWithoutEndAnchorIsRejected() {
        val json = TestSupport.sampleMeta(false).copy(complete = true).toJson()
        assertFalse(TestSupport.errors(TestSupport.metaSchema, json).isEmpty())
    }

    @Test
    fun metaViolationsAreRejected() {
        val ok = TestSupport.sampleMeta(true).toJson()
        for (bad in listOf(
            ok.replace("\"raw_format\": 1", "\"raw_format\": 2"),
            ok.replace("\"gym\"", "\"yoga\""),
            ok.replace("\"left\"", "\"right\""),
            ok.replace("\"session_id\"", "\"sid\""),
        )) {
            assertFalse(TestSupport.errors(TestSupport.metaSchema, bad).isEmpty(), bad)
        }
    }

    @Test
    fun labelsValidateAndViolationsAreRejected() {
        val ok = TestSupport.sampleLabels().toJson()
        assertEquals(emptyList<String>(), TestSupport.errors(TestSupport.labelsSchema, ok))
        for (bad in listOf(
            ok.replace("\"set\"", "\"superset\""),
            ok.replace("\"bench\"", "\"curl\""),
            ok.replace("\"labels_format\": 1", "\"labels_format\": 9"),
            ok.replace("\"participant_id\": \"P01\",", ""),
            ok.replace("\"rpe\": 8", "\"rpe\": 11"),
        )) {
            assertFalse(TestSupport.errors(TestSupport.labelsSchema, bad).isEmpty(), bad)
        }
    }

    @Test
    fun committedGoldenJsonValidates() {
        for (name in listOf(GoldenGenerator.GYM_ZIP, GoldenGenerator.CALIBRATION_ZIP)) {
            val entries = SessionZip.read(java.io.File(TestSupport.goldenDir, name))
            assertEquals(emptyList<String>(), TestSupport.errors(TestSupport.metaSchema, entries.getValue(FILE_META).decodeToString()), name)
            assertEquals(emptyList<String>(), TestSupport.errors(TestSupport.labelsSchema, entries.getValue(FILE_LABELS).decodeToString()), name)
        }
    }
}
