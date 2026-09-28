package com.pedidos360.notificaciones;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pedidos360.notificaciones.controller.NotificacionController;
import com.pedidos360.notificaciones.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificacionController.class)
class NotificacionControllerTest {

  @Autowired
  MockMvc mvc;

  @MockBean
  EmailService emailService;

  @Test
  void enviar_valido_retorna202() throws Exception {
    mvc.perform(post("/api/notificaciones/enviar")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"to":"destino@x.com","asunto":"Hola","cuerpo":"Prueba"}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.estado").value("correo_aceptado"));

    verify(emailService).enviar(eq("destino@x.com"), eq("Hola"), eq("Prueba"));
  }

  @Test
  void enviar_correoInvalido_retorna400() throws Exception {
    mvc.perform(post("/api/notificaciones/enviar")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"to":"no-es-correo","asunto":"Hola","cuerpo":"Prueba"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("parámetros inválidos"));

    verify(emailService, org.mockito.Mockito.never()).enviar(anyString(), anyString(), anyString());
  }
}
