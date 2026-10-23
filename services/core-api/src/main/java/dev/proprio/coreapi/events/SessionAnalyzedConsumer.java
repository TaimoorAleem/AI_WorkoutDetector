package dev.proprio.coreapi.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code session.analyzed} from queue {@code core.session-analyzed}. A message that is not
 * valid JSON or violates the event schema is poison: it is rejected without requeue and lands in
 * the dead letter queue. Transient failures (e.g. database down) are retried by the listener
 * container.
 */
@Component
public class SessionAnalyzedConsumer {

  private static final Logger log = LoggerFactory.getLogger(SessionAnalyzedConsumer.class);

  private final ObjectMapper mapper;
  private final AnalysisIngestService ingest;

  public SessionAnalyzedConsumer(ObjectMapper mapper, AnalysisIngestService ingest) {
    this.mapper = mapper;
    this.ingest = ingest;
  }

  @RabbitListener(queues = "${proprio.messaging.analyzed-queue}")
  public void onMessage(Message message) {
    JsonNode event;
    try {
      event = mapper.readTree(message.getBody());
    } catch (IOException e) {
      throw new AmqpRejectAndDontRequeueException("Malformed session.analyzed JSON", e);
    }
    List<JsonSchemas.Violation> violations = JsonSchemas.SESSION_ANALYZED.validate(event);
    if (!violations.isEmpty()) {
      log.warn("Rejecting invalid session.analyzed: {}", violations);
      throw new AmqpRejectAndDontRequeueException("Invalid session.analyzed: " + violations);
    }
    ingest.apply(event);
  }
}
