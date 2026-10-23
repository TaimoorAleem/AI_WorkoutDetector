package dev.proprio.coreapi.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** Loads the shared JSON Schemas (copied from contracts/ and protocol/ at build time). */
public final class JsonSchemas {

  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  public static final JsonSchemas SESSION_ANALYZED =
      new JsonSchemas("contracts/events/session-analyzed.schema.json");
  public static final JsonSchemas SESSION_UPLOADED =
      new JsonSchemas("contracts/events/session-uploaded.schema.json");
  public static final JsonSchemas LABELS = new JsonSchemas("protocol/schema/labels.schema.json");

  /** One validation failure: JSON path and message. */
  public record Violation(String path, String message) {}

  private final JsonSchema schema;

  private JsonSchemas(String classpathLocation) {
    try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
      this.schema = FACTORY.getSchema(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot load schema " + classpathLocation, e);
    }
  }

  public List<Violation> validate(JsonNode document) {
    return schema.validate(document).stream()
        .map(JsonSchemas::toViolation)
        .sorted(java.util.Comparator.comparing(Violation::path).thenComparing(Violation::message))
        .toList();
  }

  private static Violation toViolation(ValidationMessage m) {
    return new Violation(String.valueOf(m.getInstanceLocation()), m.getMessage());
  }
}
