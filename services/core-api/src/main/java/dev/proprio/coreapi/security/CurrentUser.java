package dev.proprio.coreapi.security;

import dev.proprio.coreapi.user.UserRepository;
import dev.proprio.coreapi.web.ApiException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The authenticated, still-active user of the current request. */
@Component
public class CurrentUser {

  private final UserRepository users;

  public CurrentUser(UserRepository users) {
    this.users = users;
  }

  /** User id from the validated JWT; 401 if the account was deleted since the token was issued. */
  public UUID id() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      throw ApiException.unauthorized("Missing or invalid credentials.");
    }
    UUID id;
    try {
      id = UUID.fromString(auth.getName());
    } catch (IllegalArgumentException e) {
      throw ApiException.unauthorized("Missing or invalid credentials.");
    }
    if (!users.existsByIdAndDeletedAtIsNull(id)) {
      throw ApiException.unauthorized("Missing or invalid credentials.");
    }
    return id;
  }
}
