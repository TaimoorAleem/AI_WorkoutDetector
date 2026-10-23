package dev.proprio.coreapi.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proprio.coreapi.labels.LabelRepository;
import dev.proprio.coreapi.labels.LabelService;
import dev.proprio.coreapi.session.DeviceRepository;
import dev.proprio.coreapi.session.SessionRepository;
import dev.proprio.coreapi.session.SessionService;
import dev.proprio.coreapi.web.ApiException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Every lookup is keyed by the caller's user id; someone else's id is indistinguishable from none.
 */
class UserScopingTest {

  @Test
  void foreignSessionIsNotFoundAndLookupUsesTheCallersUserId() {
    SessionRepository sessions = mock(SessionRepository.class);
    UUID userB = UUID.randomUUID();
    UUID sessionOfA = UUID.randomUUID();
    when(sessions.findBySessionIdAndUserId(sessionOfA, userB)).thenReturn(Optional.empty());

    SessionService service =
        new SessionService(
            sessions, null, null, null, null, null, new ObjectMapper(), null, Clock.systemUTC());
    assertThatThrownBy(() -> service.get(userB, sessionOfA))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

    verify(sessions).findBySessionIdAndUserId(sessionOfA, userB);
    verify(sessions, never()).findById(any());
  }

  @Test
  void labelsOfAForeignSessionAre404BeforeTheLabelTableIsTouched() {
    SessionRepository sessions = mock(SessionRepository.class);
    LabelRepository labels = mock(LabelRepository.class);
    when(sessions.findBySessionIdAndUserId(any(), any())).thenReturn(Optional.empty());
    SessionService sessionService =
        new SessionService(
            sessions, null, null, null, null, null, new ObjectMapper(), null, Clock.systemUTC());
    LabelService service =
        new LabelService(labels, sessionService, new ObjectMapper(), Clock.systemUTC());

    assertThatThrownBy(() -> service.get(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(ApiException.class);
    verify(labels, never()).findOwned(any(UUID.class), any(UUID.class));
  }

  /**
   * Structural guard: repository methods declared by us (not inherited CRUD) must take a userId,
   * except explicit, reviewed exceptions.
   */
  @Test
  void userOwnedRepositoriesOnlyExposeUserScopedQueries() {
    Set<String> reviewedExceptions = Set.of("updateIfVersion"); // only after an owned lookup
    for (Class<?> repo :
        new Class<?>[] {SessionRepository.class, LabelRepository.class, DeviceRepository.class}) {
      for (Method m : repo.getDeclaredMethods()) {
        if (Modifier.isStatic(m.getModifiers()) || reviewedExceptions.contains(m.getName())) {
          continue;
        }
        assertThat(Arrays.stream(m.getParameters()).map(p -> p.getName()))
            .as("%s.%s must be scoped by userId", repo.getSimpleName(), m.getName())
            .contains("userId");
      }
    }
  }
}
