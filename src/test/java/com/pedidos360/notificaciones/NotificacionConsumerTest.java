package com.pedidos360.notificaciones;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pedidos360.notificaciones.messaging.NotificacionConsumer;
import com.pedidos360.notificaciones.messaging.OrdenRegistradaEvent;
import com.pedidos360.notificaciones.service.EmailService;
import com.rabbitmq.client.Channel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.mail.MailSendException;

@ExtendWith(MockitoExtension.class)
class NotificacionConsumerTest {

  @Mock
  EmailService emailService;
  @Mock
  Channel channel;

  @InjectMocks
  NotificacionConsumer consumer;

  private static final long TAG = 7L;

  private static OrdenRegistradaEvent eventoValido() {
    return new OrdenRegistradaEvent(
        UUID.randomUUID(), "usuario-1",
        List.of(new OrdenRegistradaEvent.Item(UUID.randomUUID(), 1)));
  }

  @Test
  void exito_mandaCorreoYack() throws Exception {
    OrdenRegistradaEvent event = eventoValido();

    consumer.notificarOrden(event, channel, TAG, new Message(new byte[0], new MessageProperties()));

    verify(emailService).enviarConfirmacionOrden(event.ordenId(), 1);
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void invalido_dlqDirecto() throws Exception {
    OrdenRegistradaEvent event = new OrdenRegistradaEvent(UUID.randomUUID(), "u-1", List.of());

    consumer.notificarOrden(event, channel, TAG, new Message(new byte[0], new MessageProperties()));

    verify(emailService, never()).enviarConfirmacionOrden(any(), anyInt());
    verify(channel).basicNack(TAG, false, false);
  }

  @Test
  void smtpCaido_reintenta() throws Exception {
    OrdenRegistradaEvent event = eventoValido();
    Mockito.doThrow(new MailSendException("smtp caído"))
        .when(emailService).enviarConfirmacionOrden(any(), anyInt());

    consumer.notificarOrden(event, channel, TAG, new Message(new byte[0], new MessageProperties()));

    verify(channel).basicNack(TAG, false, true);
  }
}
