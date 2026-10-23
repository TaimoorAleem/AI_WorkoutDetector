package dev.proprio.coreapi.analysis;

import dev.proprio.coreapi.api.AnalysisApi;
import dev.proprio.coreapi.api.HistoryApi;
import dev.proprio.coreapi.api.model.Analysis;
import dev.proprio.coreapi.api.model.Exercise;
import dev.proprio.coreapi.api.model.LoggedSet;
import dev.proprio.coreapi.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisController implements AnalysisApi, HistoryApi {

  private final AnalysisService analysis;
  private final CurrentUser currentUser;

  public AnalysisController(AnalysisService analysis, CurrentUser currentUser) {
    this.analysis = analysis;
    this.currentUser = currentUser;
  }

  @Override
  public ResponseEntity<Analysis> getAnalysis(UUID sessionId) {
    return ResponseEntity.ok(analysis.analysis(currentUser.id(), sessionId));
  }

  @Override
  public ResponseEntity<List<LoggedSet>> listWorkouts(
      LocalDate from, LocalDate to, Exercise exercise) {
    return ResponseEntity.ok(analysis.workouts(currentUser.id(), from, to, exercise));
  }
}
