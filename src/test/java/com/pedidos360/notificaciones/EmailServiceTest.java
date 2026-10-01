package com.pedidos360.notificaciones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pedidos360.notificaciones.service.EmailService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

  @Mock
  JavaMailSender mailSender;

  @Test
  void enviar_directo_mandaAlDestino() {
    EmailService service = new EmailService(mailSender, "remitente@x.com");

    service.enviar("destino@x.com", "Hola", "Cuerpo");

    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    assertEquals("destino@x.com", captor.getValue().getTo()[0]);
    assertEquals("Hola", captor.getValue().getSubject());
  }

  @Test
  void enviar_sinDestino_fallaSinMandar() {
    EmailService service = new EmailService(mailSender, "remitente@x.com");

    assertThrows(IllegalArgumentException.class,
        () -> service.enviar("  ", "Hola", "Cuerpo"));
    verify(mailSender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void confirmacion_vaAlComprador() {
    EmailService service = new EmailService(mailSender, "remitente@x.com");
    UUID ordenId = UUID.randomUUID();

    service.enviarConfirmacionOrden(ordenId, 2, "comprador@mail.com");

    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    assertEquals("comprador@mail.com", captor.getValue().getTo()[0]);
  }

  @Test
  void confirmacion_sinEmail_fallaSinMandar() {
    EmailService service = new EmailService(mailSender, "remitente@x.com");

    assertThrows(IllegalArgumentException.class,
        () -> service.enviarConfirmacionOrden(UUID.randomUUID(), 1, "  "));
    verify(mailSender, never()).send(any(SimpleMailMessage.class));
  }
}
