package dev.proprio.coreapi.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proprio.coreapi.events.JsonSchemas;
import dev.proprio.coreapi.support.Golden;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Labels are validated against the authoritative protocol/schema/labels.schema.json. */
class LabelValidationTest {

  static final ObjectMapper MAPPER = new ObjectMapper();

  static ObjectNode golden() {
    return (ObjectNode) Golden.gymLabels().deepCopy();
  }

  @Test
  void goldenLabelsDocumentIsValid() {
    assertThat(JsonSchemas.LABELS.validate(Golden.gymLabels())).isEmpty();
  }

  @Test
  void missingRequiredFieldsAreReported() {
    ObjectNode doc = golden();
    doc.remove("participant_id");
    doc.remove("consent");
    List<JsonSchemas.Violation> v = JsonSchemas.LABELS.validate(doc);
    assertThat(v)
        .extracting(JsonSchemas.Violation::message)
        .anyMatch(m -> m.contains("participant_id"));
    assertThat(v).extracting(JsonSchemas.Violation::message).anyMatch(m -> m.contains("consent"));
  }

  @Test
  void wrongFormatVersionAndUnknownExerciseAreRejected() {
    ObjectNode doc = golden();
    doc.put("labels_format", 2);
    ((ObjectNode) ((ArrayNode) doc.get("segments")).get(0)).put("exercise", "curl");
    List<JsonSchemas.Violation> v = JsonSchemas.LABELS.validate(doc);
    assertThat(v).extracting(JsonSchemas.Violation::path).contains("$.labels_format");
    assertThat(v).extracting(JsonSchemas.Violation::path).contains("$.segments[0].exercise");
  }

  @Test
  void rpeAndRepsBoundsAreEnforced() {
    ObjectNode doc = golden();
    ObjectNode seg = (ObjectNode) ((ArrayNode) doc.get("segments")).get(0);
    seg.put("rpe", 11);
    seg.put("reps_user", -1);
    seg.put("load_kg", -5);
    assertThat(JsonSchemas.LABELS.validate(doc)).hasSizeGreaterThanOrEqualTo(3);
  }

  @Test
  void eventSchemasAreLoadedAndEnforced() {
    JsonNode bad = MAPPER.createObjectNode().put("schema_version", 1);
    assertThat(JsonSchemas.SESSION_ANALYZED.validate(bad)).isNotEmpty();
    assertThat(JsonSchemas.SESSION_UPLOADED.validate(bad)).isNotEmpty();

    ObjectNode ok = MAPPER.createObjectNode();
    ok.put("schema_version", 1);
    ok.put("event_id", UUID.randomUUID().toString());
    ok.put("occurred_at", "2026-01-01T00:00:00Z");
    ok.put("caused_by", UUID.randomUUID().toString());
    ok.put("session_id", UUID.randomUUID().toString());
    ok.put("status", "failed");
    assertThat(JsonSchemas.SESSION_ANALYZED.validate(ok)).isNotEmpty(); // failed requires error
    ok.putObject("error").put("code", "internal").put("message", "boom");
    assertThat(JsonSchemas.SESSION_ANALYZED.validate(ok)).isEmpty();
  }
}
