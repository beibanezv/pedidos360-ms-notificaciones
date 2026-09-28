package com.pedidos360.notificaciones.messaging;

import java.util.List;
import java.util.UUID;

/**
 * Espejo del evento que publica ms-orders (contrato propio por servicio).
 * El correo de confirmación no necesita el total: solo identifica la orden.
 */
public record OrdenRegistradaEvent(
    UUID ordenId,
    String usuarioId,
    List<Item> items) {

  public record Item(UUID productoId, int cantidad) {}
}
