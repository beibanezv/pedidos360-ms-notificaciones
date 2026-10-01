package com.pedidos360.notificaciones.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Envío de correos. El controlador y el consumidor llaman a estos métodos
 * de alto nivel y no conocen detalles de SMTP (mismo patrón que el
 * RabbitAdminService de ms-admin).
 */
@Service
public class EmailService {

  private final JavaMailSender mailSender;
  private final String from;

  public EmailService(JavaMailSender mailSender,
      @Value("${app.mail.from:}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  /** Envío directo (endpoint de Postman). */
  public void enviar(String to, String asunto, String cuerpo) {
    if (to == null || to.isBlank()) {
      throw new IllegalArgumentException("destinatario vacío");
    }
    SimpleMailMessage msg = new SimpleMailMessage();
    if (from != null && !from.isBlank()) {
      msg.setFrom(from);
    }
    msg.setTo(to);
    msg.setSubject(asunto);
    msg.setText(cuerpo);
    // MailException (SMTP caído, credenciales malas) es RuntimeException:
    // el consumidor la trata como error TRANSITORIO (reintenta → DLQ).
    mailSender.send(msg);
  }

  /** Confirmación de compra ante orden.registrada (va al comprador). */
  public void enviarConfirmacionOrden(UUID ordenId, int cantidadItems, String emailComprador) {
    enviar(emailComprador,
        "Pedidos360: tu orden " + ordenId + " quedó registrada",
        "Hola,\n\nTu compra de " + cantidadItems + " ítem(s) (orden " + ordenId
            + ") quedó registrada y está en preparación.\n\nGracias por comprar en Pedidos360.");
  }
}
