package dev.proprio.coreapi.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proprio.coreapi.api.model.Analysis;
import dev.proprio.coreapi.api.model.AnalysisModel;
import dev.proprio.coreapi.api.model.Exercise;
import dev.proprio.coreapi.api.model.LoggedSet;
import dev.proprio.coreapi.api.model.SegmentAnalysis;
import dev.proprio.coreapi.api.model.SessionStatus;
import dev.proprio.coreapi.labels.LabelEntity;
import dev.proprio.coreapi.labels.LabelRepository;
import dev.proprio.coreapi.session.SessionEntity;
import dev.proprio.coreapi.session.SessionMapper;
import dev.proprio.coreapi.session.SessionRepository;
import dev.proprio.coreapi.session.SessionService;
import dev.proprio.coreapi.web.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Merges model predictions with the user's labels (the user's truth wins). */
@Service
public class AnalysisService {

  private static final long MAX_RANGE_DAYS = 366;

  private final SessionService sessions;
  private final SessionRepository sessionRepo;
  private final PredictionRepository predictions;
  private final LabelRepository labels;
  private final ObjectMapper mapper;

  public AnalysisService(
      SessionService sessions,
      SessionRepository sessionRepo,
      PredictionRepository predictions,
      LabelRepository labels,
      ObjectMapper mapper) {
    this.sessions = sessions;
    this.sessionRepo = sessionRepo;
    this.predictions = predictions;
    this.labels = labels;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public Analysis analysis(UUID userId, UUID sessionId) {
    SessionEntity s = sessions.requireOwned(userId, sessionId);
    return build(
        userId, s, predictions.latestForOwned(userId, sessionId), labelSegments(userId, sessionId));
  }

  /** Same shape as {@link #analysis}, for callers that already hold the session (export). */
  @Transactional(readOnly = true)
  public Analysis analysisFor(UUID userId, SessionEntity s) {
    return build(
        userId,
        s,
        predictions.latestForOwned(userId, s.getSessionId()),
        labelSegments(userId, s.getSessionId()));
  }

  private Analysis build(
      UUID userId,
      SessionEntity s,
      List<PredictionRepository.Prediction> preds,
      Map<Integer, JsonNode> userSegs) {
    List<SegmentAnalysis> segments = new ArrayList<>();
    for (PredictionRepository.Prediction p : preds) {
      SegmentAnalysis a =
          new SegmentAnalysis(
              p.segmentId(),
              SegmentAnalysis.KindEnum.fromValue(p.kind()),
              BigDecimal.valueOf(p.startS()),
              BigDecimal.valueOf(p.endS()));
      if (p.predictedExercise() != null) {
        a.setPredictedExercise(Exercise.fromValue(p.predictedExercise()));
      }
      if (p.predictedKind() != null) {
        a.setPredictedKind(SegmentAnalysis.PredictedKindEnum.fromValue(p.predictedKind()));
      }
      if (p.confidence() != null) {
        a.setConfidence(BigDecimal.valueOf(p.confidence()));
      }
      a.setRepCount(p.repCount());
      JsonNode user = userSegs.get(p.segmentId());
      if (user != null) {
        if (user.hasNonNull("exercise")) {
          a.setUserExercise(Exercise.fromValue(user.get("exercise").asText()));
        }
        if (user.hasNonNull("reps_user")) {
          a.setUserReps(user.get("reps_user").asInt());
        }
        if (user.hasNonNull("load_kg")) {
          a.setLoadKg(user.get("load_kg").decimalValue());
        }
      }
      segments.add(a);
    }
    Analysis out = new Analysis(s.getSessionId(), SessionStatus.fromValue(s.getStatus()), segments);
    if (!preds.isEmpty()) {
      PredictionRepository.Prediction first = preds.get(0);
      AnalysisModel m = new AnalysisModel();
      m.setName(first.modelName());
      m.setVersion(first.modelVersion());
      out.setModel(m);
      out.setProfileId(first.profileId());
      out.setTransformId(first.transformId());
    }
    return out;
  }

  /** The user's logged sets in [from, to] (UTC days), labels first, predictions as fallback. */
  @Transactional(readOnly = true)
  public List<LoggedSet> workouts(UUID userId, LocalDate from, LocalDate to, Exercise exercise) {
    if (to.isBefore(from)
        || Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() > MAX_RANGE_DAYS) {
      throw ApiException.validation(
          "Invalid date range.",
          List.of(new ApiException.FieldError("to", "Must be on/after 'from' and within a year.")));
    }
    List<SessionEntity> inRange =
        sessionRepo.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            userId,
            from.atStartOfDay().toInstant(ZoneOffset.UTC),
            to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    if (inRange.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = inRange.stream().map(SessionEntity::getSessionId).toList();
    Map<UUID, List<PredictionRepository.Prediction>> predsBySession =
        predictions.latestForOwned(userId, ids).stream()
            .collect(Collectors.groupingBy(PredictionRepository.Prediction::sessionId));
    Map<UUID, LabelEntity> labelsBySession =
        labels.findOwned(userId, ids).stream()
            .collect(Collectors.toMap(LabelEntity::getSessionId, Function.identity()));

    List<LoggedSet> out = new ArrayList<>();
    for (SessionEntity s : inRange) {
      Map<Integer, JsonNode> user = segmentsOf(labelsBySession.get(s.getSessionId()));
      List<PredictionRepository.Prediction> preds =
          predsBySession.getOrDefault(s.getSessionId(), List.of());
      Map<Integer, PredictionRepository.Prediction> predBySeg =
          preds.stream()
              .collect(
                  Collectors.toMap(
                      PredictionRepository.Prediction::segmentId, Function.identity()));

      // Every segment id known from either source, in order.
      List<Integer> segIds =
          java.util.stream.Stream.concat(user.keySet().stream(), predBySeg.keySet().stream())
              .distinct()
              .sorted()
              .toList();
      for (int segId : segIds) {
        Optional<LoggedSet> set = merge(s, segId, user.get(segId), predBySeg.get(segId));
        set.filter(x -> exercise == null || x.getExercise() == exercise).ifPresent(out::add);
      }
    }
    out.sort(Comparator.comparing(LoggedSet::getDate).thenComparing(LoggedSet::getSegmentId));
    return out;
  }

  private static Optional<LoggedSet> merge(
      SessionEntity s, int segId, JsonNode user, PredictionRepository.Prediction pred) {
    boolean userSays = user != null;
    // The user's classification of the segment wins; "not a set" removes it from history.
    if (userSays && !"set".equals(user.path("kind").asText())) {
      return Optional.empty();
    }
    String userExercise =
        userSays && user.hasNonNull("exercise") ? user.get("exercise").asText() : null;
    boolean predictedSet =
        pred != null
            && "set".equals(pred.kind())
            && pred.predictedExercise() != null
            && !"rest".equals(pred.predictedKind());
    if (userExercise == null && !predictedSet) {
      return Optional.empty();
    }
    boolean fromUser = userExercise != null;
    Exercise exercise = Exercise.fromValue(fromUser ? userExercise : pred.predictedExercise());
    double startS = pred != null ? pred.startS() : 0.0;
    LoggedSet set =
        new LoggedSet(
            s.getSessionId(),
            segId,
            SessionMapper.utc(s.getStartedAt().plusMillis((long) (startS * 1000))),
            exercise,
            fromUser ? LoggedSet.SourceEnum.USER : LoggedSet.SourceEnum.MODEL);
    if (userSays && user.hasNonNull("reps_user")) {
      set.setReps(user.get("reps_user").asInt());
    } else if (pred != null && pred.repCount() != null) {
      set.setReps(pred.repCount());
    }
    if (userSays && user.hasNonNull("load_kg")) {
      set.setLoadKg(user.get("load_kg").decimalValue());
    }
    if (userSays && user.hasNonNull("rpe")) {
      set.setRpe(user.get("rpe").decimalValue());
    }
    return Optional.of(set);
  }

  private Map<Integer, JsonNode> labelSegments(UUID userId, UUID sessionId) {
    return segmentsOf(labels.findOwned(userId, sessionId).orElse(null));
  }

  private Map<Integer, JsonNode> segmentsOf(LabelEntity label) {
    Map<Integer, JsonNode> out = new HashMap<>();
    if (label == null) {
      return out;
    }
    JsonNode doc = mapper.valueToTree(label.getDocument());
    for (JsonNode seg : doc.path("segments")) {
      if (seg.hasNonNull("segment_id")) {
        out.put(seg.get("segment_id").asInt(), seg);
      }
    }
    return out;
  }
}
