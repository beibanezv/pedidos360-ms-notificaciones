package com.pedidos360.notificaciones.messaging;

import com.pedidos360.notificaciones.config.RabbitMQConfig;
import com.pedidos360.notificaciones.service.EmailService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumidor del dominio "notificaciones".
 *
 * Decisiones de error (indicador 5):
 * - Evento inválido (incluido email vacío: sin destinatario no hay nada
 *   que intentar) → PERMANENTE → DLQ directo.
 * - Fallo de SMTP (MailException) → TRANSITORIO → requeue hasta
 *   MAX_REINTENTOS, después DLQ. Ojo: si las credenciales están mal, el
 *   mensaje va a gastar los 3 reintentos y caer a la DLQ; el síntoma se ve
 *   en los logs y en la DLQ, no se pierde en silencio.
 */
@Component
public class NotificacionConsumer extends BaseConsumer {

  private final EmailService emailService;

  public NotificacionConsumer(EmailService emailService) {
    this.emailService = emailService;
  }

  @Override
  protected int maxReintentos() {
    return RabbitMQConfig.MAX_REINTENTOS;
  }

  @RabbitListener(queues = RabbitMQConfig.NOTIFICACIONES_QUEUE)
  public void notificarOrden(OrdenRegistradaEvent event, Channel channel,
      @Header(AmqpHeaders.DELIVERY_TAG) long tag, Message raw) throws IOException {
    consumir(channel, tag, raw, () -> {
      if (event == null || event.ordenId() == null
          || event.emailComprador() == null || event.emailComprador().isBlank()
          || event.items() == null || event.items().isEmpty()) {
        throw new IllegalArgumentException("orden.registrada inválida: " + event);
      }
      emailService.enviarConfirmacionOrden(event.ordenId(), event.items().size(),
          event.emailComprador());
    });
  }
}
