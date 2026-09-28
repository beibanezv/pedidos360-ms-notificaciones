package com.pedidos360.notificaciones.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología RabbitMQ que usa ms-notificaciones (rúbrica EP3/EP4 indicadores 1 y 2).
 *
 * Ruta documentada:
 *   ms-orders --topic--> p360.eventos / orden.registrada
 *                            └─> p360.notificaciones.queue (la consume NotificacionConsumer)
 *   Si el mensaje muere va a p360.dlx con routing key = nombre de la cola
 *   origen, y de ahí a p360.notificaciones.dlq.
 */
@Configuration
public class RabbitMQConfig {

  public static final String EVENTOS_EXCHANGE = "p360.eventos";
  public static final String NOTIFICACIONES_QUEUE = "p360.notificaciones.queue";
  public static final String ORDEN_REGISTRADA_ROUTING_KEY = "orden.registrada";

  public static final int MAX_REINTENTOS = 3;

  public static final String DLX_EXCHANGE = "p360.dlx";
  public static final String NOTIFICACIONES_DLQ = "p360.notificaciones.dlq";

  @Bean
  TopicExchange eventosExchange() {
    return new TopicExchange(EVENTOS_EXCHANGE, true, false);
  }

  @Bean
  Queue notificacionesQueue() {
    return QueueBuilder.durable(NOTIFICACIONES_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", NOTIFICACIONES_QUEUE)
        .build();
  }

  @Bean
  Binding notificacionesBinding(Queue notificacionesQueue, TopicExchange eventosExchange) {
    return new Binding(NOTIFICACIONES_QUEUE, Binding.DestinationType.QUEUE,
        EVENTOS_EXCHANGE, ORDEN_REGISTRADA_ROUTING_KEY, null);
  }

  @Bean
  DirectExchange deadLetterExchange() {
    return new DirectExchange(DLX_EXCHANGE, true, false);
  }

  @Bean
  Queue notificacionesDlq() {
    return QueueBuilder.durable(NOTIFICACIONES_DLQ).build();
  }

  @Bean
  Binding notificacionesDlqBinding(Queue notificacionesDlq, DirectExchange deadLetterExchange) {
    return new Binding(NOTIFICACIONES_DLQ, Binding.DestinationType.QUEUE,
        DLX_EXCHANGE, NOTIFICACIONES_QUEUE, null);
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}
