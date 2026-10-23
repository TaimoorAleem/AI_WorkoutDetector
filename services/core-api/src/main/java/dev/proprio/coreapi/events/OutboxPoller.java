package dev.proprio.coreapi.events;

import dev.proprio.coreapi.config.ProprioProperties;
import dev.proprio.coreapi.session.StorageService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox. Delivery is at-least-once: a row is marked published only after the broker
 * confirmed it and it was routed to at least one queue (mandatory publish). If the process dies
 * between confirm and commit the row is sent again; consumers dedupe on {@code event_id}.
 */
@Component
public class OutboxPoller {

  private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
  private static final long BACKOFF_MS = 3_000;

  private final OutboxRepository outbox;
  private final RabbitTemplate rabbit;
  private final StorageService storage;
  private final int batchSize;
  private final Clock clock;
  private volatile Instant retryNotBefore = Instant.EPOCH;

  public OutboxPoller(
      OutboxRepository outbox,
      RabbitTemplate rabbit,
      StorageService storage,
      ProprioProperties props,
      Clock clock) {
    this.outbox = outbox;
    this.rabbit = rabbit;
    this.storage = storage;
    this.batchSize = props.outbox().batchSize();
    this.clock = clock;
    rabbit.setMandatory(true);
  }

  @Scheduled(fixedDelayString = "${proprio.outbox.poll-interval:500ms}")
  public void poll() {
    if (clock.instant().isBefore(retryNotBefore)) {
      return;
    }
    try {
      drain();
    } catch (RuntimeException e) {
      retryNotBefore = clock.instant().plusMillis(BACKOFF_MS);
      log.warn("Outbox drain failed, retrying later: {}", e.toString());
    }
  }

  /** Publishes pending rows in order; stops at the first failure to preserve ordering. */
  @Transactional
  public int drain() {
    List<OutboxRepository.Row> rows = outbox.lockPending(batchSize);
    int done = 0;
    for (OutboxRepository.Row row : rows) {
      try {
        switch (row.kind()) {
          case AMQP -> publish(row);
          case S3_DELETE -> storage.delete(row.payload());
        }
        outbox.markPublished(row.id());
        done++;
      } catch (RuntimeException
          | InterruptedException
          | java.util.concurrent.ExecutionException
          | java.util.concurrent.TimeoutException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        outbox.markAttempted(row.id());
        retryNotBefore = clock.instant().plusMillis(BACKOFF_MS);
        log.warn("Outbox row {} ({}) not delivered: {}", row.id(), row.kind(), e.toString());
        break;
      }
    }
    return done;
  }

  private void publish(OutboxRepository.Row row)
      throws InterruptedException,
          java.util.concurrent.ExecutionException,
          java.util.concurrent.TimeoutException {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setContentEncoding(StandardCharsets.UTF_8.name());
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(row.id().toString());
    props.setType(row.routingKey());
    props.setTimestamp(Date.from(clock.instant()));
    Message message =
        MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
            .andProperties(props)
            .build();
    CorrelationData correlation = new CorrelationData(row.id().toString());
    rabbit.send(row.exchange(), row.routingKey(), message, correlation);
    CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
    if (!confirm.isAck()) {
      throw new IllegalStateException("Broker nack: " + confirm.getReason());
    }
    if (correlation.getReturned() != null) {
      throw new IllegalStateException(
          "Unroutable (no queue bound to " + row.routingKey() + "): will retry");
    }
  }
}
