package com.pedidos360.notificaciones.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores claros en la API (rúbrica EP3/EP4 indicador 8): si el DTO trae
 * un correo inválido o campos vacíos, el 400 dice exactamente qué falló.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException e) {
    String detalle = e.getBindingResult().getFieldErrors().stream()
        .map(f -> f.getField() + ": " + f.getDefaultMessage())
        .sorted()
        .toList()
        .toString();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "parámetros inválidos", "detalle", detalle));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> argumento(IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", e.getMessage() == null ? "parámetro inválido" : e.getMessage()));
  }
}
