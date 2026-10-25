package dev.proprio.coreapi.labels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proprio.coreapi.events.JsonSchemas;
import dev.proprio.coreapi.session.SessionService;
import dev.proprio.coreapi.web.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LabelService {

  /** A stored document plus its ETag. */
  public record Versioned(LabelsDocument document, String etag) {}

  private final LabelRepository labels;
  private final SessionService sessions;
  private final ObjectMapper mapper;
  private final Clock clock;

  public LabelService(
      LabelRepository labels, SessionService sessions, ObjectMapper mapper, Clock clock) {
    this.labels = labels;
    this.sessions = sessions;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Versioned get(UUID userId, UUID sessionId) {
    sessions.requireOwned(userId, sessionId);
    LabelEntity l = labels.findOwned(userId, sessionId).orElseThrow(ApiException::notFound);
    return toVersioned(l);
  }

  /**
   * Validates against labels.schema.json, checks that the document's session_id matches the path,
   * and applies If-Match optimistic concurrency.
   */
  @Transactional
  public Versioned put(UUID userId, UUID sessionId, LabelsDocument body, String ifMatch) {
    sessions.requireOwned(userId, sessionId); // 404 before anything about the body is revealed
    JsonNode node = mapper.valueToTree(body);
    List<ApiException.FieldError> errors = validate(node, sessionId);
    if (!errors.isEmpty()) {
      throw ApiException.validation("Labels document is invalid.", errors);
    }
    Map<String, Object> doc = new LinkedHashMap<>(body);
    LabelEntity existing = labels.findOwned(userId, sessionId).orElse(null);
    if (existing == null) {
      if (ifMatch != null && !"*".equals(ifMatch.trim())) {
        throw ApiException.preconditionFailed("No labels exist yet; If-Match does not match.");
      }
      try {
        labels.saveAndFlush(new LabelEntity(sessionId, doc, clock.instant()));
      } catch (DataIntegrityViolationException e) {
        throw ApiException.preconditionFailed("Labels were created concurrently.");
      }
    } else {
      if (ifMatch != null && !matches(ifMatch, existing.getVersion())) {
        throw ApiException.preconditionFailed("If-Match does not match the current ETag.");
      }
      int updated = labels.updateIfVersion(sessionId, existing.getVersion(), doc, clock.instant());
      if (updated == 0) {
        throw ApiException.preconditionFailed("Labels were modified concurrently.");
      }
    }
    return toVersioned(labels.findOwned(userId, sessionId).orElseThrow(ApiException::notFound));
  }

  static List<ApiException.FieldError> validate(JsonNode node, UUID sessionId) {
    List<ApiException.FieldError> errors = new ArrayList<>();
    for (JsonSchemas.Violation v : JsonSchemas.LABELS.validate(node)) {
      errors.add(new ApiException.FieldError(v.path(), v.message()));
    }
    if (errors.isEmpty()
        && !sessionId.toString().equalsIgnoreCase(node.path("session_id").asText())) {
      errors.add(
          new ApiException.FieldError("$.session_id", "Must equal the session id in the path."));
    }
    return errors;
  }

  /** ETag as sent to clients (quoted). */
  public static String etag(int version) {
    return "\"" + version + "\"";
  }

  /** If-Match may be {@code *}, a quoted/weak ETag, or a comma-separated list. */
  static boolean matches(String ifMatch, int version) {
    for (String part : ifMatch.split(",")) {
      String p = part.trim();
      if (p.equals("*")) {
        return true;
      }
      if (p.startsWith("W/")) {
        p = p.substring(2);
      }
      if (p.equals(etag(version))) {
        return true;
      }
    }
    return false;
  }

  private Versioned toVersioned(LabelEntity l) {
    LabelsDocument doc = new LabelsDocument();
    doc.putAll(l.getDocument());
    return new Versioned(doc, etag(l.getVersion()));
  }
}
