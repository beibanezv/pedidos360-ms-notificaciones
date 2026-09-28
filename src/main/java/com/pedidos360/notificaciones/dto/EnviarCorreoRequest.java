package com.pedidos360.notificaciones.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Disparo directo de correo (endpoint de Postman que exige el enunciado). */
public record EnviarCorreoRequest(
    @NotBlank(message = "to no puede estar vacío")
    @Email(message = "to debe ser un correo válido")
    String to,

    @NotBlank(message = "asunto no puede estar vacío")
    String asunto,

    @NotBlank(message = "cuerpo no puede estar vacío")
    String cuerpo) {}
