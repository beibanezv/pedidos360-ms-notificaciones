import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.notificaciones.config.RabbitMQConfig;
import com.pedidos360.notificaciones.messaging.NotificacionConsumer;
import com.pedidos360.notificaciones.messaging.OrdenRegistradaEvent;
import com.pedidos360.notificaciones.service.EmailService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

/**
 * Helper de integración para probar M06b contra un broker RabbitMQ REAL.
 *
 * No arranca el contenedor de Spring (que tiene la cola fija
 * p360.notificaciones.queue) ni configura trustedPackages: declara una
 * topología aislada con nombres únicos y llama DIRECTAMENTE al
 * NotificacionConsumer real, heredero de BaseConsumer, con un Channel real y
 * con el Jackson2JsonMessageConverter real de RabbitMQConfig.
 *
 * El "efecto" (envío de correo) se sustituye por un EmailService de prueba que
 * publica un evento de auditoría en una cola del MISMO broker: así el script
 * puede contar intentos y confirmar ack/nack consultando el estado real de las
 * colas, no solo los logs.
 */
public final class M06bRabbitHelper {

  private static final String RK = "p360.m06b.orden.registrada";
  private static final String AUDIT_RK = "p360.m06b.audit";
  private static final ObjectMapper JSON = new ObjectMapper();

  private static String prop(String Nombre) {
    return System.getProperty(Nombre);
  }

  private static String host() { return prop("p360.m06b.host"); }
  private static int port() { return Integer.parseInt(prop("p360.m06b.port")); }
  private static String vhost() { return prop("p360.m06b.vhost"); }
  private static String user() { return prop("p360.m06b.user"); }
  private static String pass() { return prop("p360.m06b.pass"); }
  private static String ex() { return prop("p360.m06b.ex"); }
  private static String dlx() { return prop("p360.m06b.dlx"); }
  private static String auditEx() { return prop("p360.m06b.audit-ex"); }
  private static String queue() { return prop("p360.m06b.queue"); }
  private static String dlq() { return prop("p360.m06b.dlq"); }
  private static String auditQueue() { return prop("p360.m06b.audit-queue"); }

  private static ConnectionFactory factory() {
    ConnectionFactory f = new ConnectionFactory();
    f.setHost(host());
    f.setPort(port());
    f.setVirtualHost(vhost());
    f.setUsername(user());
    f.setPassword(pass());
    f.setConnectionTimeout(10000);
    return f;
  }

  private static Jackson2JsonMessageConverter converter() {
    // mapper REAL del repo: __TypeId__ nombre simple + alias FQCN históricos.
    Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
    converter.setJavaTypeMapper(RabbitMQConfig.jsonTypeMapper());
    return converter;
  }

  private static void audit(Channel ch, String caso, String orden, String event,
      long flag, boolean redeliver, boolean requeue, String motivo) {
    try {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("event", event);
      m.put("caso", caso);
      m.put("orden", orden);
      m.put("tag", flag);
      m.put("redeliver", redeliver);
      m.put("requeue", requeue);
      m.put("ts", System.currentTimeMillis());
      if (motivo != null) {
        m.put("motivo", motivo);
      }
      AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
          .contentType("application/json").deliveryMode(2).build();
      ch.basicPublish(auditEx(), AUDIT_RK, props, JSON.writeValueAsBytes(m));
      ch.waitForConfirmsOrDie(5000);
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo auditar en el broker", e);
    }
  }

  private static void preparar() throws Exception {
    try (Connection c = factory().newConnection(); Channel ch = c.createChannel()) {
      ch.exchangeDeclare(ex(), "topic", true, false, null);
      ch.exchangeDeclare(dlx(), "direct", true, false, null);
      ch.exchangeDeclare(auditEx(), "direct", true, false, null);

      // Igual que RabbitMQConfig: DLX con routing key = nombre de la cola origen.
      Map<String, Object> args = new HashMap<>();
      args.put("x-dead-letter-exchange", dlx());
      args.put("x-dead-letter-routing-key", queue());
      ch.queueDeclare(queue(), true, false, false, args);
      ch.queueBind(queue(), ex(), RK);
      ch.queueDeclare(dlq(), true, false, false, null);
      ch.queueBind(dlq(), dlx(), queue());
      ch.queueDeclare(auditQueue(), true, false, false, null);
      ch.queueBind(auditQueue(), auditEx(), AUDIT_RK);
      System.out.println("M06B preparar OK ex=" + ex() + " q=" + queue()
          + " dlq=" + dlq() + " auditQ=" + auditQueue());
    }
  }

  private static void publicar(String caso, String fallo, String ordenStr) throws Exception {
    UUID orden = UUID.fromString(ordenStr);
    OrdenRegistradaEvent event;
    if ("permanente".equals(fallo)) {
      // items vacíos => IllegalArgumentException real dentro de NotificacionConsumer.
      event = new OrdenRegistradaEvent(orden, "u-m06b", "comprador@m06b.local",
          List.of(), null);
    } else {
      event = new OrdenRegistradaEvent(orden, "u-m06b", "comprador@m06b.local",
          List.of(new OrdenRegistradaEvent.Item(UUID.randomUUID(), 2)), null);
    }

    MessageProperties mp = new MessageProperties();
    mp.setContentType("application/json");
    mp.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    mp.setHeader("x-p360-caso", caso);
    mp.setHeader("x-p360-orden", orden.toString());
    mp.setHeader("x-p360-fallo", fallo);
    Message out = converter().toMessage(event, mp);

    // El converter real etiqueta el tipo (__TypeId__) en MessageProperties; lo
    // pasamos al canal Rabbit como BasicProperties conservando TODOS los headers.
    AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
        .contentType(out.getMessageProperties().getContentType())
        .deliveryMode(2)
        .headers(new HashMap<>(out.getMessageProperties().getHeaders()))
        .build();
    try (Connection c = factory().newConnection(); Channel ch = c.createChannel()) {
      ch.confirmSelect();
      ch.basicPublish(ex(), RK, props, out.getBody());
      ch.waitForConfirmsOrDie(5000);
    }
    System.out.println("M06B publicar OK caso=" + caso + " fallo=" + fallo
        + " orden=" + orden + " typeId=" + out.getMessageProperties().getHeader("__TypeId__"));
  }

  /** EmailService de prueba: cada llamada deja una huella real en la cola de auditoría. */
  private static final class EmailDePrueba extends EmailService {
    private final String fallo;
    private final Channel audit;
    private final String caso;
    private final String orden;
    private int intentos;

    EmailDePrueba(String fallo, Channel audit, String caso, String orden) {
      super(null, "no-reply@m06b.local");
      this.fallo = fallo;
      this.audit = audit;
      this.caso = caso;
      this.orden = orden;
    }

    @Override
    public void enviarConfirmacionOrden(UUID ordenId, int cantidadItems, String emailComprador) {
      intentos++;
      audit(audit, caso, orden, "efecto", intentos, false, false, "intento=" + intentos);
      System.out.println("M06B " + caso + " efecto correo #" + intentos
          + " (simulado, publicado en cola de auditoría)");
      if ("transitorio".equals(fallo)) {
        throw new RuntimeException("SMTP simulado caído en intento " + intentos);
      }
    }
  }

  private static void consumir(String caso, boolean crash) throws Exception {
    Connection c = factory().newConnection();
    Connection ca = factory().newConnection();
    try (Connection cAuto = c; Connection caAuto = ca; Channel ch = cAuto.createChannel()) {
      ch.basicQos(1);
      Channel auditCh = caAuto.createChannel();
      auditCh.confirmSelect();
      final String[] ordenActual = new String[1];

      // Proxy del Channel real: intercepta basicAck/basicNack para auditarlos
      // y delega la llamada al canal Rabbit de verdad.
      Channel proxy = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
          new Class<?>[] {Channel.class}, (p, method, args) -> {
            if (args != null && "basicAck".equals(method.getName())) {
              long tag = (Long) args[0];
              audit(auditCh, caso, ordenActual[0], "ack", tag, false, false, null);
              System.out.println("M06B " + caso + " basicAck tag=" + tag);
            } else if (args != null && "basicNack".equals(method.getName())) {
              long tag = (Long) args[0];
              boolean requeue = (Boolean) args[2];
              audit(auditCh, caso, ordenActual[0], "nack", tag, false, requeue,
                  requeue ? "requeue" : "dlq");
              System.out.println("M06B " + caso + " basicNack tag=" + tag
                  + " requeue=" + requeue);
            }
            try {
              return method.invoke(ch, args);
            } catch (InvocationTargetException e) {
              throw e.getCause();
            }
          });

      CountDownLatch latch = new CountDownLatch(1);
      DefaultConsumer listener = new DefaultConsumer(ch) {
        @Override
        public void handleDelivery(String consumerTag, Envelope envelope,
            AMQP.BasicProperties props, byte[] body) throws IOException {
          try {
            MessageProperties mp = new MessageProperties();
            mp.setContentType(props.getContentType());
            if (props.getHeaders() != null) {
              mp.setHeaders(new HashMap<>(props.getHeaders()));
            }
            Message raw = new Message(body, mp);
            OrdenRegistradaEvent event =
                (OrdenRegistradaEvent) converter().fromMessage(raw, OrdenRegistradaEvent.class);
            String casoM = String.valueOf(props.getHeaders().get("x-p360-caso"));
            String ordenM = String.valueOf(props.getHeaders().get("x-p360-orden"));
            String falloM = String.valueOf(props.getHeaders().get("x-p360-fallo"));
            ordenActual[0] = ordenM;

            audit(auditCh, casoM, ordenM, "recibido", envelope.getDeliveryTag(),
                envelope.isRedeliver(), false, null);
            System.out.println("M06B " + casoM + " recibido tag=" + envelope.getDeliveryTag()
                + " redeliver=" + envelope.isRedeliver() + " orden=" + ordenM);

            if (crash) {
              // Caída abrupta DESPUÉS de recibir y ANTES de ejecutar el efecto:
              // el mensaje queda sin confirmar; el broker lo reentregará.
              audit(auditCh, casoM, ordenM, "crash", envelope.getDeliveryTag(), false, false,
                  "halt 23 antes del efecto");
              System.out.println("M06B " + casoM + " CRASH simulado (halt 23), sin ack ni efecto");
              Runtime.getRuntime().halt(23);
            }

            // El objeto bajo prueba: consumidor REAL (hereda BaseConsumer) con un
            // Canal REAL y un EmailService que provoca el fallo indicado.
            NotificacionConsumer real =
                new NotificacionConsumer(new EmailDePrueba(falloM, auditCh, casoM, ordenM));
            real.notificarOrden(event, proxy, envelope.getDeliveryTag(), raw);
          } catch (Exception e) {
            System.out.println("M06B ERROR en " + caso + ": " + e);
            e.printStackTrace();
            try {
              ch.basicNack(envelope.getDeliveryTag(), false, false);
            } catch (Exception ignorado) {
              // el broker ya cerró el canal
            }
          } finally {
            latch.countDown();
          }
        }
      };

      ch.basicConsume(queue(), false, listener);
      if (!latch.await(60, TimeUnit.SECONDS)) {
        System.out.println("M06B TIMEOUT esperando mensaje en " + queue());
        System.exit(3);
      }
      auditCh.close();
      System.out.println("M06B " + caso + " consumidor finalizado");
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Uso: M06bRabbitHelper preparar | publicar <caso> <fallo> <uuid>"
          + " | consumir <caso> [crash]");
      System.exit(2);
    }
    String cmd = args[0];
    switch (cmd) {
      case "preparar":
        preparar();
        break;
      case "publicar":
        if (args.length < 4) {
          throw new IllegalArgumentException("publicar requiere: caso fallo uuid");
        }
        publicar(args[1], args[2], args[3]);
        break;
      case "consumir":
        if (args.length < 2) {
          throw new IllegalArgumentException("consumir requiere: caso [crash]");
        }
        consumir(args[1], args.length > 2 && "crash".equals(args[2]));
        break;
      default:
        throw new IllegalArgumentException("Comando desconocido: " + cmd);
    }
  }
}
