package dev.proprio.coreapi.session;

import dev.proprio.coreapi.api.model.Session;
import dev.proprio.coreapi.api.model.SessionKind;
import dev.proprio.coreapi.api.model.SessionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class SessionMapper {

  private SessionMapper() {}

  public static OffsetDateTime utc(Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  public static Session toDto(SessionEntity s, String deviceModel) {
    Session dto =
        new Session(
            s.getSessionId(),
            SessionKind.fromValue(s.getKind()),
            SessionStatus.fromValue(s.getStatus()),
            utc(s.getStartedAt()),
            utc(s.getCreatedAt()));
    if (s.getDurationS() != null) {
      dto.setDurationS(BigDecimal.valueOf(s.getDurationS()));
    }
    dto.setDeviceModel(deviceModel);
    dto.setAnalyzedAt(utc(s.getAnalyzedAt()));
    dto.setFailureReason(s.getFailureReason());
    return dto;
  }
}
