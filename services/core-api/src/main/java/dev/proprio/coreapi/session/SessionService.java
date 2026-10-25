package dev.proprio.coreapi.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proprio.coreapi.api.model.CreateSessionRequest;
import dev.proprio.coreapi.api.model.Session;
import dev.proprio.coreapi.api.model.SessionPage;
import dev.proprio.coreapi.api.model.UploadTicket;
import dev.proprio.coreapi.config.ProprioProperties;
import dev.proprio.coreapi.events.AmqpConfig;
import dev.proprio.coreapi.events.OutboxRepository;
import dev.proprio.coreapi.user.ConsentRepository;
import dev.proprio.coreapi.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

  private final SessionRepository sessions;
  private final DeviceRepository devices;
  private final ConsentRepository consents;
  private final OutboxRepository outbox;
  private final StorageService storage;
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final ProprioProperties props;
  private final Clock clock;

  public SessionService(
      SessionRepository sessions,
      DeviceRepository devices,
      ConsentRepository consents,
      OutboxRepository outbox,
      StorageService storage,
      JdbcClient jdbc,
      ObjectMapper mapper,
      ProprioProperties props,
      Clock clock) {
    this.sessions = sessions;
    this.devices = devices;
    this.consents = consents;
    this.outbox = outbox;
    this.storage = storage;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.props = props;
    this.clock = clock;
  }

  /** Registers (or re-registers while CREATED) a session and returns a presigned PUT ticket. */
  @Transactional
  public UploadTicket create(UUID userId, CreateSessionRequest req) {
    if (req.getSizeBytes() > props.upload().maxSizeBytes()) {
      throw ApiException.validation(
          "Upload too large.",
          List.of(new ApiException.FieldError("sizeBytes", "Must be at most the upload limit.")));
    }
    Instant now = clock.instant();
    var dev = req.getDevice();
    DeviceEntity device =
        devices
            .findByUserIdAndModel(userId, dev.getModel())
            .orElseGet(
                () ->
                    devices.save(
                        new DeviceEntity(
                            userId, dev.getManufacturer(), dev.getModel(), dev.getSdkInt(), now)));

    SessionEntity session = sessions.findById(req.getSessionId()).orElse(null);
    if (session != null) {
      // Another user's id and a non-CREATED session look the same to the caller: 409.
      if (!session.getUserId().equals(userId)
          || !SessionEntity.CREATED.equals(session.getStatus())) {
        throw ApiException.conflict("A session with this id already exists.");
      }
    } else {
      session =
          new SessionEntity(req.getSessionId(), userId, objectKey(userId, req.getSessionId()), now);
    }
    session.describe(
        device.getId(),
        req.getKind().getValue(),
        req.getStartedAt().toInstant(),
        req.getDurationS() == null ? null : req.getDurationS().doubleValue(),
        req.getSha256(),
        req.getSizeBytes(),
        req.getRawFormat().getValue());
    sessions.save(session);

    StorageService.PresignedPut put =
        storage.presignPut(session.getObjectKey(), session.getSizeBytes());
    UploadTicket ticket = new UploadTicket();
    ticket.setSession(SessionMapper.toDto(session, device.getModel()));
    ticket.setUploadUrl(java.net.URI.create(put.url()));
    ticket.setUploadMethod(UploadTicket.UploadMethodEnum.PUT);
    ticket.setUploadHeaders(new HashMap<>(put.headers()));
    ticket.setExpiresAt(SessionMapper.utc(put.expiresAt()));
    return ticket;
  }

  /** Verifies the object, flips CREATED to UPLOADED and enqueues session.uploaded atomically. */
  @Transactional
  public Session complete(UUID userId, UUID sessionId) {
    SessionEntity s = sessions.findForUpdate(sessionId, userId).orElseThrow(ApiException::notFound);
    String model = deviceModel(userId, s);
    switch (s.getStatus()) {
      case SessionEntity.UPLOADED, SessionEntity.ANALYZING:
        return SessionMapper.toDto(s, model); // idempotent retry from the client
      case SessionEntity.CREATED:
        break;
      default:
        throw ApiException.conflict("Session is already " + s.getStatus() + ".");
    }
    Long size = storage.sizeOf(s.getObjectKey()).orElse(null);
    if (size == null) {
      throw ApiException.unprocessable("The uploaded object was not found.");
    }
    if (size != s.getSizeBytes()) {
      throw ApiException.unprocessable(
          "Uploaded object size " + size + " does not match declared " + s.getSizeBytes() + ".");
    }
    Instant now = clock.instant();
    s.markUploaded(now);
    sessions.save(s);

    boolean trainingConsent =
        consents
            .findFirstByUserIdOrderByRecordedAtDesc(userId)
            .map(c -> c.isTrainingUse())
            .orElse(false);
    UUID eventId = UUID.randomUUID();
    ObjectNode event = mapper.createObjectNode();
    event.put("schema_version", 1);
    event.put("event_id", eventId.toString());
    event.put("occurred_at", now.toString());
    event.put("session_id", s.getSessionId().toString());
    event.put("user_id", userId.toString());
    event.put("object_key", s.getObjectKey());
    event.put("sha256", s.getSha256());
    event.put("size_bytes", s.getSizeBytes());
    event.put("raw_format", s.getRawFormat());
    event.put("kind", s.getKind());
    event.put("device_model", model);
    event.put("training_consent", trainingConsent);
    outbox.enqueueEvent(
        eventId,
        props.messaging().exchange(),
        AmqpConfig.ROUTING_UPLOADED,
        event.toString(),
        userId);
    return SessionMapper.toDto(s, model);
  }

  @Transactional(readOnly = true)
  public Session get(UUID userId, UUID sessionId) {
    SessionEntity s = requireOwned(userId, sessionId);
    return SessionMapper.toDto(s, deviceModel(userId, s));
  }

  /** The caller's session, or 404 (also for someone else's id). */
  public SessionEntity requireOwned(UUID userId, UUID sessionId) {
    return sessions.findBySessionIdAndUserId(sessionId, userId).orElseThrow(ApiException::notFound);
  }

  /** Newest first, keyset-paginated on (started_at, session_id). */
  @Transactional(readOnly = true)
  public SessionPage list(
      UUID userId, OffsetDateTime from, OffsetDateTime to, int limit, String cursor) {
    StringBuilder sql =
        new StringBuilder("select session_id from sessions where user_id = :userId");
    Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);
    if (from != null) {
      sql.append(" and started_at >= :from");
      params.put("from", java.sql.Timestamp.from(from.toInstant()));
    }
    if (to != null) {
      sql.append(" and started_at <= :to");
      params.put("to", java.sql.Timestamp.from(to.toInstant()));
    }
    if (cursor != null) {
      Cursor c = Cursor.decode(cursor);
      sql.append(" and (started_at, session_id) < (:cs, :cid)");
      params.put("cs", java.sql.Timestamp.from(c.startedAt()));
      params.put("cid", c.sessionId());
    }
    sql.append(" order by started_at desc, session_id desc limit :lim");
    params.put("lim", limit + 1);
    List<UUID> ids = jdbc.sql(sql.toString()).params(params).query(UUID.class).list();

    boolean more = ids.size() > limit;
    List<UUID> pageIds = more ? ids.subList(0, limit) : ids;
    Map<UUID, SessionEntity> byId =
        sessions.findByUserIdAndSessionIdIn(userId, pageIds).stream()
            .collect(Collectors.toMap(SessionEntity::getSessionId, Function.identity()));
    Map<UUID, String> models = deviceModels(userId, byId.values());
    List<Session> items = new ArrayList<>();
    for (UUID id : pageIds) {
      SessionEntity s = byId.get(id);
      items.add(SessionMapper.toDto(s, models.get(s.getDeviceId())));
    }
    SessionPage page = new SessionPage(items);
    if (more) {
      SessionEntity last = byId.get(pageIds.get(pageIds.size() - 1));
      page.setNextCursor(new Cursor(last.getStartedAt(), last.getSessionId()).encode());
    }
    return page;
  }

  private String deviceModel(UUID userId, SessionEntity s) {
    return devices
        .findByIdAndUserId(s.getDeviceId(), userId)
        .map(DeviceEntity::getModel)
        .orElse(null);
  }

  private Map<UUID, String> deviceModels(UUID userId, java.util.Collection<SessionEntity> list) {
    Map<UUID, String> out = new HashMap<>();
    for (SessionEntity s : list) {
      out.computeIfAbsent(s.getDeviceId(), id -> deviceModel(userId, s));
    }
    return out;
  }

  static String objectKey(UUID userId, UUID sessionId) {
    return "raw/" + userId + "/" + sessionId + ".zip";
  }

  /** Opaque pagination cursor. */
  record Cursor(Instant startedAt, UUID sessionId) {
    String encode() {
      String raw = startedAt.getEpochSecond() + "." + startedAt.getNano() + "|" + sessionId;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) {
      try {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|");
        String[] ts = parts[0].split("\\.");
        return new Cursor(
            Instant.ofEpochSecond(Long.parseLong(ts[0]), Long.parseLong(ts[1])),
            UUID.fromString(parts[1]));
      } catch (RuntimeException e) {
        throw ApiException.validation(
            "Invalid cursor.", List.of(new ApiException.FieldError("cursor", "Invalid cursor.")));
      }
    }
  }
}
