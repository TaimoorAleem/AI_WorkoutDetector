package dev.proprio.coreapi.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/** Disables Testcontainers-based tests (instead of failing) when no Docker daemon is reachable. */
public class DockerCondition implements ExecutionCondition {

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    boolean available;
    try {
      available = DockerClientFactory.instance().isDockerAvailable();
    } catch (RuntimeException e) {
      available = false;
    }
    return available
        ? ConditionEvaluationResult.enabled("Docker available")
        : ConditionEvaluationResult.disabled("Docker not available: skipping integration test");
  }
}
