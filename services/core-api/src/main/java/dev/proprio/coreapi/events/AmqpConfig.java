package dev.proprio.coreapi.events;

import dev.proprio.coreapi.config.ProprioProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology owned by core-api: the shared topic exchange and its own consumer queue (with a dead
 * letter queue for poison messages). Queues of other services are declared by those services.
 */
@Configuration
public class AmqpConfig {

  public static final String ROUTING_UPLOADED = "session.uploaded";
  public static final String ROUTING_ANALYZED = "session.analyzed";

  @Bean
  TopicExchange eventsExchange(ProprioProperties props) {
    return ExchangeBuilder.topicExchange(props.messaging().exchange()).durable(true).build();
  }

  @Bean
  FanoutExchange deadLetterExchange(ProprioProperties props) {
    return new FanoutExchange(props.messaging().exchange() + ".dlx", true, false);
  }

  @Bean
  Queue analyzedQueue(ProprioProperties props) {
    return QueueBuilder.durable(props.messaging().analyzedQueue())
        .deadLetterExchange(props.messaging().exchange() + ".dlx")
        .build();
  }

  @Bean
  Queue analyzedDeadLetterQueue(ProprioProperties props) {
    return QueueBuilder.durable(props.messaging().analyzedQueue() + ".dlq").build();
  }

  @Bean
  Binding analyzedBinding(Queue analyzedQueue, TopicExchange eventsExchange) {
    return BindingBuilder.bind(analyzedQueue).to(eventsExchange).with(ROUTING_ANALYZED);
  }

  @Bean
  Binding analyzedDeadLetterBinding(
      Queue analyzedDeadLetterQueue, FanoutExchange deadLetterExchange) {
    return BindingBuilder.bind(analyzedDeadLetterQueue).to(deadLetterExchange);
  }
}
