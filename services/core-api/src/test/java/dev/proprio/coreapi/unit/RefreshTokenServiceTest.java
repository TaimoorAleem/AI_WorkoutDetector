package dev.proprio.coreapi.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.proprio.coreapi.auth.RefreshTokenEntity;
import dev.proprio.coreapi.auth.RefreshTokenRepository;
import dev.proprio.coreapi.auth.RefreshTokenService;
import dev.proprio.coreapi.config.ProprioProperties;
import dev.proprio.coreapi.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Rotation and reuse detection against an in-memory repository (no database). */
class RefreshTokenServiceTest {

  final List<RefreshTokenEntity> store = new ArrayList<>();
  RefreshTokenService service;
  Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
    when(repo.save(any()))
        .thenAnswer(
            inv -> {
              store.add(inv.getArgument(0));
              return inv.getArgument(0);
            });
    when(repo.findByTokenHash(anyString()))
        .thenAnswer(
            inv -> store.stream().filter(t -> hashOf(t).equals(inv.getArgument(0))).findFirst());
    when(repo.findByTokenHashAndUserId(anyString(), any()))
        .thenAnswer(
            inv ->
                store.stream()
                    .filter(t -> hashOf(t).equals(inv.getArgument(0)))
                    .filter(t -> t.getUserId().equals(inv.getArgument(1)))
                    .findFirst());
    when(repo.revokeFamily(any(), any()))
        .thenAnswer(
            inv -> {
              UUID family = inv.getArgument(0);
              int n = 0;
              for (RefreshTokenEntity t : store) {
                if (t.getFamilyId().equals(family) && t.getRevokedAt() == null) {
                  revoke(t, inv.getArgument(1));
                  n++;
                }
              }
              return n;
            });
    ProprioProperties props =
        new ProprioProperties(
            null,
            new ProprioProperties.Auth(Duration.ofDays(30), null, null),
            null,
            null,
            null,
            null);
    service = new RefreshTokenService(repo, props, clock);
  }

  // The entity stores only the hash; recover it reflectively for the fake repository.
  private static String hashOf(RefreshTokenEntity t) {
    return (String) field(t, "tokenHash");
  }

  private static void revoke(RefreshTokenEntity t, Instant when) {
    try {
      var f = RefreshTokenEntity.class.getDeclaredField("revokedAt");
      f.setAccessible(true);
      f.set(t, when);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object field(Object o, String name) {
    try {
      var f = o.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(o);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha(String raw) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rawTokenIsNeverStoredOnlyItsHash() throws Exception {
    String raw = service.issueNewFamily(UUID.randomUUID());
    assertThat(raw).hasSizeGreaterThanOrEqualTo(43); // 256 bits, base64url
    assertThat(store).hasSize(1);
    assertThat(hashOf(store.get(0))).isEqualTo(sha(raw)).isNotEqualTo(raw);
  }

  @Test
  void rotationIssuesNewTokenInSameFamilyAndMarksOldRotated() {
    UUID user = UUID.randomUUID();
    String first = service.issueNewFamily(user);
    RefreshTokenService.Issued second = service.rotate(first);

    assertThat(second.userId()).isEqualTo(user);
    assertThat(second.rawToken()).isNotEqualTo(first);
    assertThat(store).hasSize(2);
    assertThat(store.get(0).getRotatedAt()).isNotNull();
    assertThat(store.get(1).getRotatedAt()).isNull();
    assertThat(store.get(1).getFamilyId()).isEqualTo(store.get(0).getFamilyId());
  }

  @Test
  void reusingARotatedTokenRevokesTheWholeFamily() {
    UUID user = UUID.randomUUID();
    String first = service.issueNewFamily(user);
    String second = service.rotate(first).rawToken();

    assertThatThrownBy(() -> service.rotate(first))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));

    // The legitimate holder's newest token is now dead too.
    assertThat(store).allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
    assertThatThrownBy(() -> service.rotate(second)).isInstanceOf(ApiException.class);
  }

  @Test
  void otherFamiliesAreUnaffectedByReuse() {
    UUID user = UUID.randomUUID();
    String laptop = service.issueNewFamily(user);
    String phone = service.issueNewFamily(user);
    String laptop2 = service.rotate(laptop).rawToken();
    assertThatThrownBy(() -> service.rotate(laptop)).isInstanceOf(ApiException.class);

    assertThat(service.rotate(phone).userId()).isEqualTo(user);
    assertThatThrownBy(() -> service.rotate(laptop2)).isInstanceOf(ApiException.class);
  }

  @Test
  void unknownAndExpiredTokensAreRejected() {
    assertThatThrownBy(() -> service.rotate("nope")).isInstanceOf(ApiException.class);

    UUID user = UUID.randomUUID();
    String raw = service.issueNewFamily(user);
    Clock later = Clock.fixed(clock.instant().plus(Duration.ofDays(31)), ZoneOffset.UTC);
    RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
    when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(store.get(0)));
    ProprioProperties props =
        new ProprioProperties(
            null,
            new ProprioProperties.Auth(Duration.ofDays(30), null, null),
            null,
            null,
            null,
            null);
    var expiredView = new RefreshTokenService(repo, props, later);
    assertThatThrownBy(() -> expiredView.rotate(raw))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void logoutRevokesFamilyOnlyForTheOwner() {
    UUID owner = UUID.randomUUID();
    String raw = service.issueNewFamily(owner);

    service.revokeFamilyOf(raw, UUID.randomUUID()); // someone else: ignored
    assertThat(store.get(0).getRevokedAt()).isNull();

    service.revokeFamilyOf(raw, owner);
    assertThat(store.get(0).getRevokedAt()).isNotNull();
    assertThatThrownBy(() -> service.rotate(raw)).isInstanceOf(ApiException.class);
  }
}
