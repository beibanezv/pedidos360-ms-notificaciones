package com.pedidos360.notificaciones.controller;

import com.pedidos360.notificaciones.dto.EnviarCorreoRequest;
import com.pedidos360.notificaciones.service.EmailService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Disparo directo de correos (para probar desde Postman, como pide el
 * enunciado). Sin Spring Security a propósito: en AWS lo protege el
 * authorizer JWT del API Gateway (igual que ms-admin y ms-orders).
 */
@RestController
@RequestMapping("/api/notificaciones")
public class NotificacionController {

  private final EmailService emailService;

  public NotificacionController(EmailService emailService) {
    this.emailService = emailService;
  }

  @PostMapping("/enviar")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, String> enviar(@Valid @RequestBody EnviarCorreoRequest req) {
    emailService.enviar(req.to(), req.asunto(), req.cuerpo());
    return Map.of("estado", "correo_aceptado", "to", req.to());
  }
}
