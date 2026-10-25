package dev.proprio.coreapi.auth;

import dev.proprio.coreapi.api.AuthApi;
import dev.proprio.coreapi.api.model.LoginRequest;
import dev.proprio.coreapi.api.model.RefreshRequest;
import dev.proprio.coreapi.api.model.RegisterRequest;
import dev.proprio.coreapi.api.model.TokenPair;
import dev.proprio.coreapi.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController implements AuthApi {

  private final AuthService auth;
  private final CurrentUser currentUser;
  private final HttpServletRequest request;

  public AuthController(AuthService auth, CurrentUser currentUser, HttpServletRequest request) {
    this.auth = auth;
    this.currentUser = currentUser;
    this.request = request;
  }

  @Override
  public ResponseEntity<TokenPair> register(RegisterRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(auth.register(body.getEmail(), body.getPassword(), body.getParticipantId()));
  }

  @Override
  public ResponseEntity<TokenPair> login(LoginRequest body) {
    // remoteAddr only: X-Forwarded-For is client-controlled unless a trusted proxy sets it.
    return ResponseEntity.ok(
        auth.login(body.getEmail(), body.getPassword(), request.getRemoteAddr()));
  }

  @Override
  public ResponseEntity<TokenPair> refresh(RefreshRequest body) {
    return ResponseEntity.ok(auth.refresh(body.getRefreshToken()));
  }

  @Override
  public ResponseEntity<Void> logout(RefreshRequest body) {
    auth.logout(body.getRefreshToken(), currentUser.id());
    return ResponseEntity.noContent().build();
  }
}
