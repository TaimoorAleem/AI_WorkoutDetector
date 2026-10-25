package dev.proprio.coreapi.labels;

import dev.proprio.coreapi.api.LabelsApi;
import dev.proprio.coreapi.security.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LabelsController implements LabelsApi {

  private final LabelService labels;
  private final CurrentUser currentUser;

  public LabelsController(LabelService labels, CurrentUser currentUser) {
    this.labels = labels;
    this.currentUser = currentUser;
  }

  @Override
  public ResponseEntity<LabelsDocument> getLabels(UUID sessionId) {
    return respond(labels.get(currentUser.id(), sessionId));
  }

  @Override
  public ResponseEntity<LabelsDocument> putLabels(
      UUID sessionId, LabelsDocument body, String ifMatch) {
    return respond(labels.put(currentUser.id(), sessionId, body, ifMatch));
  }

  private static ResponseEntity<LabelsDocument> respond(LabelService.Versioned v) {
    return ResponseEntity.ok().header(HttpHeaders.ETAG, v.etag()).body(v.document());
  }
}
