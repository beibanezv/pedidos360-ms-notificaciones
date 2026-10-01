package com.pedidos360.notificaciones.messaging;

import java.util.List;
import java.util.UUID;

/**
 * Espejo del evento que publica ms-orders (contrato propio por servicio).
 * La confirmación se envía a emailComprador (obligatorio desde el checkout).
 * codigoCupon viaja para ms-cupones; aquí se ignora, PERO el campo debe
 * existir: Jackson es estricto y sin él todo evento real cae a la DLQ
 * (UnrecognizedPropertyException). Ver espejo de ms-productos.
 */
public record OrdenRegistradaEvent(
    UUID ordenId,
    String usuarioId,
    String emailComprador,
    List<Item> items,
    String codigoCupon) {

  public record Item(UUID productoId, int cantidad) {}
}
