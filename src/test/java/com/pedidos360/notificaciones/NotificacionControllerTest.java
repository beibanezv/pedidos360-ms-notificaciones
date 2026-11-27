package com.pedidos360.notificaciones;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pedidos360.notificaciones.config.SecurityConfig;
import com.pedidos360.notificaciones.controller.NotificacionController;
import com.pedidos360.notificaciones.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(NotificacionController.class)
@Import(SecurityConfig.class)
class NotificacionControllerTest {

  @Autowired
  MockMvc mvc;

  @MockBean
  EmailService emailService;

  @MockBean
  JwtDecoder jwtDecoder;

  private static final String CUERPO = """
      {"to":"destino@x.com","asunto":"Hola","cuerpo":"Prueba"}""";

  private static MockHttpServletRequestBuilder conAdmin(MockHttpServletRequestBuilder peticion) {
    return peticion.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin")));
  }

  @Test
  void enviar_valido_retorna202() throws Exception {
    mvc.perform(conAdmin(post("/api/notificaciones/enviar")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CUERPO)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.estado").value("correo_aceptado"));

    verify(emailService).enviar(eq("destino@x.com"), eq("Hola"), eq("Prueba"));
  }

  @Test
  void enviar_correoInvalido_retorna400() throws Exception {
    mvc.perform(conAdmin(post("/api/notificaciones/enviar")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"to":"no-es-correo","asunto":"Hola","cuerpo":"Prueba"}""")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("parámetros inválidos"));

    verify(emailService, org.mockito.Mockito.never()).enviar(anyString(), anyString(), anyString());
  }

  @Test
  void enviar_anonimo_retorna401() throws Exception {
    mvc.perform(post("/api/notificaciones/enviar")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CUERPO))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(emailService);
  }

  @Test
  void enviar_clienteAzure_retorna403() throws Exception {
    mvc.perform(post("/api/notificaciones/enviar")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CUERPO)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Cliente"))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(emailService);
  }

  @Test
  void enviar_jwtInvalido_retorna401() throws Exception {
    when(jwtDecoder.decode("invalido")).thenThrow(new BadJwtException("token inválido"));

    mvc.perform(post("/api/notificaciones/enviar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer invalido")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CUERPO))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(emailService);
  }

  @Test
  void preflight_origenPermitido_retornaOkConOrigenExacto() throws Exception {
    mvc.perform(options("/api/notificaciones/enviar")
            .header(HttpHeaders.ORIGIN, "http://localhost:4200")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"));

    verifyNoInteractions(emailService);
  }

  @Test
  void preflight_origenAjeno_retorna403SinOrigen() throws Exception {
    mvc.perform(options("/api/notificaciones/enviar")
            .header(HttpHeaders.ORIGIN, "http://atacante.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

    verifyNoInteractions(emailService);
  }
}
