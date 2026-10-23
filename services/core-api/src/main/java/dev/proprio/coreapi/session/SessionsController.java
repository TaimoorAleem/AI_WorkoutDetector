package dev.proprio.coreapi.session;

import dev.proprio.coreapi.api.SessionsApi;
import dev.proprio.coreapi.api.model.CreateSessionRequest;
import dev.proprio.coreapi.api.model.Session;
import dev.proprio.coreapi.api.model.SessionPage;
import dev.proprio.coreapi.api.model.UploadTicket;
import dev.proprio.coreapi.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionsController implements SessionsApi {

  private final SessionService sessions;
  private final CurrentUser currentUser;

  public SessionsController(SessionService sessions, CurrentUser currentUser) {
    this.sessions = sessions;
    this.currentUser = currentUser;
  }

  @Override
  public ResponseEntity<UploadTicket> createSession(CreateSessionRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(sessions.create(currentUser.id(), body));
  }

  @Override
  public ResponseEntity<SessionPage> listSessions(
      OffsetDateTime from, OffsetDateTime to, Integer limit, String cursor) {
    return ResponseEntity.ok(sessions.list(currentUser.id(), from, to, limit, cursor));
  }

  @Override
  public ResponseEntity<Session> getSession(UUID sessionId) {
    return ResponseEntity.ok(sessions.get(currentUser.id(), sessionId));
  }

  @Override
  public ResponseEntity<Session> completeUpload(UUID sessionId) {
    return ResponseEntity.accepted().body(sessions.complete(currentUser.id(), sessionId));
  }
}
