package dev.proprio.coreapi.user;

import dev.proprio.coreapi.analysis.AnalysisService;
import dev.proprio.coreapi.api.model.Consent;
import dev.proprio.coreapi.api.model.DataExport;
import dev.proprio.coreapi.api.model.DataExportSessionsInner;
import dev.proprio.coreapi.api.model.User;
import dev.proprio.coreapi.auth.RefreshTokenRepository;
import dev.proprio.coreapi.events.OutboxRepository;
import dev.proprio.coreapi.labels.LabelRepository;
import dev.proprio.coreapi.labels.LabelsDocument;
import dev.proprio.coreapi.session.DeviceRepository;
import dev.proprio.coreapi.session.SessionEntity;
import dev.proprio.coreapi.session.SessionMapper;
import dev.proprio.coreapi.session.SessionRepository;
import dev.proprio.coreapi.session.StorageService;
import dev.proprio.coreapi.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

  private static final Duration EXPORT_URL_TTL = Duration.ofHours(1);

  private final UserRepository users;
  private final ConsentRepository consents;
  private final SessionRepository sessions;
  private final DeviceRepository devices;
  private final RefreshTokenRepository refreshTokens;
  private final LabelRepository labels;
  private final OutboxRepository outbox;
  private final AnalysisService analysis;
  private final StorageService storage;
  private final Clock clock;

  public AccountService(
      UserRepository users,
      ConsentRepository consents,
      SessionRepository sessions,
      DeviceRepository devices,
      RefreshTokenRepository refreshTokens,
      LabelRepository labels,
      OutboxRepository outbox,
      AnalysisService analysis,
      StorageService storage,
      Clock clock) {
    this.users = users;
    this.consents = consents;
    this.sessions = sessions;
    this.devices = devices;
    this.refreshTokens = refreshTokens;
    this.labels = labels;
    this.outbox = outbox;
    this.analysis = analysis;
    this.storage = storage;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public User me(UUID userId) {
    UserEntity u = users.findByIdAndDeletedAtIsNull(userId).orElseThrow(ApiException::notFound);
    return toUser(u);
  }

  @Transactional
  public Consent setConsent(UUID userId, boolean trainingUse) {
    ConsentEntity c = consents.save(new ConsentEntity(userId, trainingUse, clock.instant()));
    return toConsent(c);
  }

  @Transactional(readOnly = true)
  public DataExport export(UUID userId) {
    UserEntity u = users.findByIdAndDeletedAtIsNull(userId).orElseThrow(ApiException::notFound);
    DataExport out = new DataExport();
    out.setUser(toUser(u));
    out.setConsentHistory(
        consents.findByUserIdOrderByRecordedAtAsc(userId).stream()
            .map(AccountService::toConsent)
            .toList());
    List<DataExportSessionsInner> items = new ArrayList<>();
    List<SessionEntity> all = new ArrayList<>(sessions.findByUserId(userId));
    all.sort(Comparator.comparing(SessionEntity::getStartedAt));
    Map<UUID, String> models = new java.util.HashMap<>();
    for (SessionEntity s : all) {
      String model =
          models.computeIfAbsent(
              s.getDeviceId(),
              id -> devices.findByIdAndUserId(id, userId).map(d -> d.getModel()).orElse(null));
      DataExportSessionsInner item = new DataExportSessionsInner();
      item.setSession(SessionMapper.toDto(s, model));
      labels
          .findOwned(userId, s.getSessionId())
          .ifPresent(
              l -> {
                LabelsDocument doc = new LabelsDocument();
                doc.putAll(l.getDocument());
                item.setLabels(doc);
              });
      item.setAnalysis(analysis.analysisFor(userId, s));
      if (!SessionEntity.CREATED.equals(s.getStatus())) {
        item.setRawDownloadUrl(
            java.net.URI.create(storage.presignGet(s.getObjectKey(), EXPORT_URL_TTL)));
      }
      items.add(item);
    }
    out.setSessions(items);
    return out;
  }

  /**
   * Deletes the user's rows immediately and queues deletion of the raw objects through the outbox,
   * so a crash can't leave objects behind. The user row stays as an anonymized tombstone.
   */
  @Transactional
  public void delete(UUID userId) {
    UserEntity u = users.findByIdAndDeletedAtIsNull(userId).orElseThrow(ApiException::notFound);
    for (SessionEntity s : sessions.findByUserId(userId)) {
      outbox.enqueueObjectDelete(s.getObjectKey()); // CREATED sessions may have a partial upload
    }
    outbox.deletePendingEventsForUser(userId);
    sessions.deleteAllForUser(userId); // labels and predictions cascade
    devices.deleteAllForUser(userId);
    refreshTokens.deleteAllForUser(userId);
    consents.deleteAll(consents.findByUserIdOrderByRecordedAtAsc(userId));
    u.anonymize(clock.instant());
    users.save(u);
  }

  private User toUser(UserEntity u) {
    Consent consent =
        consents
            .findFirstByUserIdOrderByRecordedAtDesc(u.getId())
            .map(AccountService::toConsent)
            .orElseGet(() -> new Consent(false, SessionMapper.utc(u.getCreatedAt())));
    return new User(
        u.getId(),
        u.getEmail(),
        u.getParticipantId(),
        SessionMapper.utc(u.getCreatedAt()),
        consent);
  }

  private static Consent toConsent(ConsentEntity c) {
    return new Consent(c.isTrainingUse(), SessionMapper.utc(c.getRecordedAt()));
  }
}
