# pedidos360-ms-notificaciones

Microservicio de notificaciones de Pedidos360 (EP3/EP4 — DSY1107).
Sin base de datos: consume un evento y manda un correo.

## Qué hace

1. Consume `orden.registrada` de `p360.notificaciones.queue`.
2. Manda el correo de confirmación al `emailComprador` del evento
   (obligatorio desde el checkout: sin email no hay compra).
3. Expone `POST /api/notificaciones/enviar` para disparo directo
   (lo exige el enunciado: debe poder probarse desde Postman).

## Desarrollo local

```powershell
# el broker vive en pedidos360-ms-admin/docker/rabbitmq-cluster
$env:RABBITMQ_HOST="localhost"
# correo real solo con credenciales por env (app password de Gmail):
$env:MAIL_USERNAME="tu.cuenta@gmail.com"
$env:MAIL_PASSWORD="xxxx xxxx xxxx xxxx"
mvn spring-boot:run           # puerto 8085
```

Probar directo (Postman o curl):

```powershell
curl -X POST http://localhost:8085/api/notificaciones/enviar `
  -H "Content-Type: application/json" `
  -d '{"to":"destino@ejemplo.com","asunto":"Hola","cuerpo":"Prueba"}'
```
