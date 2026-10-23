package dev.proprio.coreapi.web;

import dev.proprio.coreapi.api.OpsApi;
import dev.proprio.coreapi.api.model.Health;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contract's {@code /health}, derived from the actuator health tree. DOWN (database down)
 * answers 503 so container/orchestrator probes fail; a broker outage only degrades the service,
 * since uploads and reads still work and the outbox catches up later.
 */
@RestController
public class HealthController implements OpsApi {

  private final HealthEndpoint health;

  public HealthController(HealthEndpoint health) {
    this.health = health;
  }

  @Override
  public ResponseEntity<Health> health() {
    HealthComponent root = health.health();
    Map<String, Health.InnerEnum> components = new LinkedHashMap<>();
    if (root instanceof CompositeHealth composite) {
      composite
          .getComponents()
          .forEach(
              (name, c) ->
                  components.put(
                      name,
                      Status.UP.equals(c.getStatus())
                          ? Health.InnerEnum.UP
                          : Health.InnerEnum.DOWN));
    }
    boolean dbDown = components.get("db") == Health.InnerEnum.DOWN;
    boolean anyDown = components.containsValue(Health.InnerEnum.DOWN);
    Health.StatusEnum status =
        dbDown
            ? Health.StatusEnum.DOWN
            : anyDown ? Health.StatusEnum.DEGRADED : Health.StatusEnum.UP;
    Health body = new Health(status);
    body.setComponents(components);
    return ResponseEntity.status(dbDown ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
        .body(body);
  }
}
