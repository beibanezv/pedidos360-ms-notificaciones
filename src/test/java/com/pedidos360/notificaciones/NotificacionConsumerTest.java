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
  static { System.setProperty("p360.retry.espera-ms", "0"); }

  @Mock
  EmailService emailService;
  @Mock
  Channel channel;

  @InjectMocks
  NotificacionConsumer consumer;

  private static final long TAG = 7L;

  private static OrdenRegistradaEvent eventoValido() {
    return new OrdenRegistradaEvent(
        UUID.randomUUID(), "usuario-1", "comprador@mail.com",
        List.of(new OrdenRegistradaEvent.Item(UUID.randomUUID(), 1)), null);
  }

  @Test
  void exito_mandaCorreoYack() throws Exception {
    OrdenRegistradaEvent event = eventoValido();

    consumer.notificarOrden(event, channel, TAG, new Message(new byte[0], new MessageProperties()));

    verify(emailService).enviarConfirmacionOrden(event.ordenId(), 1, "comprador@mail.com");
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void invalido_dlqDirecto() throws Exception {
    OrdenRegistradaEvent event = new OrdenRegistradaEvent(UUID.randomUUID(), "u-1",
        "comprador@mail.com", List.of(), null);

    consumer.notificarOrden(event, channel, TAG, new Message(new byte[0], new MessageProperties()));

    verify(emailService, never()).enviarConfirmacionOrden(any(), anyInt(), any());
    verify(channel).basicNack(TAG, false, false);
  }

  @Test
  void sinEmail_dlqDirecto() throws Exception {
    OrdenRegistradaEvent event = new OrdenRegistradaEvent(UUID.randomUUID(), "u-1", "  ",
        List.of(new OrdenRegistradaEvent.Item(UUID.randomUUID(), 1)), null);

    consumer.notificarOrden(event, channel, TAG, new Message(new byte[0], new MessageProperties()));

    verify(emailService, never()).enviarConfirmacionOrden(any(), anyInt(), any());
    verify(channel).basicNack(TAG, false, false);
  }

  @Test
  void smtpCaido_reintenta() throws Exception {
    OrdenRegistradaEvent event = eventoValido();
    Mockito.doThrow(new MailSendException("smtp caído"))
        .when(emailService).enviarConfirmacionOrden(any(), anyInt(), any());

    consumer.notificarOrden(event, channel, TAG, new Message(new byte[0], new MessageProperties()));

    verify(channel).basicNack(TAG, false, false);
  }

  // Regresión Fase 6 (AWS): el evento real trae campo codigoCupon (agregado
  // en Fase 4). Jackson es estricto: sin codigoCupon en el espejo, todo
  // evento real caía a la DLQ (UnrecognizedPropertyException). El listener
  // tipado ignora el __TypeId__ foráneo (precedente: ms-productos consume
  // el mismo evento sin mapeos), así que el contrato que importa es el JSON.
  @Test
  void eventoRealDelBroker_deserializaConCuponYEmail() throws Exception {
    UUID orden = UUID.randomUUID();
    UUID prod = UUID.randomUUID();
    String json = "{\"ordenId\":\"" + orden + "\",\"usuarioId\":\"u-1\","
        + "\"emailComprador\":\"comprador@mail.com\",\"items\":"
        + "[{\"productoId\":\"" + prod + "\",\"cantidad\":1}],\"codigoCupon\":null}";

    OrdenRegistradaEvent out =
        new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, OrdenRegistradaEvent.class);

    org.junit.jupiter.api.Assertions.assertEquals(orden, out.ordenId());
    org.junit.jupiter.api.Assertions.assertEquals("comprador@mail.com", out.emailComprador());
    org.junit.jupiter.api.Assertions.assertEquals(1, out.items().size());
  }
}
