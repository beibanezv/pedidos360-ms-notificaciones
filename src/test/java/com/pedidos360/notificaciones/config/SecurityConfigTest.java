package com.pedidos360.notificaciones.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ejercita el JwtDecoder que construye produccion: claves RSA efimeras publicadas
 * en un HttpServer de 127.0.0.1, sin internet, DB ni broker. Los campos de config
 * se llenan igual que lo hace Spring con @Value.
 */
class SecurityConfigTest {

  private static final String TENANT = "tenant-de-prueba";
  private static final String AZURE_ISSUER = "https://sts.windows.net/" + TENANT + "/";
  private static final String AZURE_AUDIENCE = "api://audiencia-de-prueba";
  private static final String COGNITO_ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_otro";

  private SecurityConfig config;
  private HttpServer server;
  private AtomicInteger peticiones;
  private Clave azure;
  private Clave ajena;

  @BeforeEach
  void setUp() throws Exception {
    azure = new Clave("clave-azure");
    ajena = new Clave("clave-ajena");
    peticiones = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/" + TENANT + "/discovery/keys",
        exchange -> responder(exchange, azure.jwks(), peticiones));
    server.start();

    config = new SecurityConfig();
    ReflectionTestUtils.setField(config, "issuerUri", base + "/");
    ReflectionTestUtils.setField(config, "tenantId", TENANT);
    ReflectionTestUtils.setField(config, "azureAudience", AZURE_AUDIENCE);
    ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:4200");
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private static void responder(HttpExchange exchange, String jwks, AtomicInteger contador) {
    contador.incrementAndGet();
    byte[] cuerpo = jwks.getBytes(StandardCharsets.UTF_8);
    try {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, cuerpo.length);
      try (OutputStream salida = exchange.getResponseBody()) {
        salida.write(cuerpo);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String firmar(Clave clave, String issuer, String audience, Instant expira, Map<String, Object> claims) {
    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date.from(Instant.now()));
    if (issuer != null) builder.issuer(issuer);
    if (audience != null) builder.audience(audience);
    if (expira != null) builder.expirationTime(Date.from(expira));
    if (claims != null) claims.forEach(builder::claim);
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.publica.getKeyID()).build(),
        builder.build());
    try {
      jwt.sign(new RSASSASigner(clave.privada));
    } catch (JOSEException e) {
      throw new IllegalStateException("No se pudo firmar el token de prueba", e);
    }
    return jwt.serialize();
  }

  private String azure(Map<String, Object> claims) {
    return firmar(azure, AZURE_ISSUER, AZURE_AUDIENCE, Instant.now().plusSeconds(600), claims);
  }

  private List<String> autoridades(Jwt jwt) {
    return config.jwtAuthenticationConverter().convert(jwt)
        .getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList());
  }

  private static final class Clave {
    private final RSAPrivateKey privada;
    private final RSAKey publica;

    private Clave(String kid) {
      try {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair par = generator.generateKeyPair();
        this.privada = (RSAPrivateKey) par.getPrivate();
        this.publica = new RSAKey.Builder((RSAPublicKey) par.getPublic()).keyID(kid).build();
      } catch (Exception e) {
        throw new IllegalStateException("No se pudo generar la llave de prueba", e);
      }
    }

    private String jwks() {
      return new JWKSet(publica.toPublicJWK()).toString();
    }
  }

  @Test
  void converter_mapeaRolesDeEntra() {
    Jwt jwt = Jwt.withTokenValue("access-token-azure")
        .header("alg", "RS256")
        .subject("sub-123")
        .claim("oid", "oid-123")
        .claim("roles", List.of("Admin"))
        .build();

    assertTrue(autoridades(jwt).contains("ROLE_Admin"));
  }

  @Test
  void converter_tokenSinRoles_noDaAdmin() {
    Jwt jwt = Jwt.withTokenValue("access-token-azure")
        .header("alg", "RS256")
        .subject("sub-123")
        .build();

    assertTrue(autoridades(jwt).isEmpty());
  }

  @Test
  void aceptaAzureV1ConAudienceCorrecta() {
    Jwt jwt = config.jwtDecoder().decode(azure(Map.of(
        "scp", "Productos.Read",
        "roles", List.of("Admin"),
        "oid", "oid-123")));

    assertEquals(AZURE_ISSUER, jwt.getIssuer().toString());
    assertEquals("oid-123", jwt.getClaimAsString("oid"));
    assertTrue(autoridades(jwt).contains("ROLE_Admin"));
  }

  @Test
  void rechazaFirmaAjena() {
    String token = firmar(ajena, AZURE_ISSUER, AZURE_AUDIENCE, Instant.now().plusSeconds(600), Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaIssuerAjenoAunqueLaFirmaSeaValida() {
    String token = firmar(azure, "https://atacante.example/", AZURE_AUDIENCE,
        Instant.now().plusSeconds(600), Map.of());
    JwtDecoder decoder = config.jwtDecoder();

    assertThrows(JwtException.class, () -> decoder.decode(token));
  }

  @Test
  void rechazaCognitoAunqueLaFirmaSeaValida() {
    // Un ID token de Cognito firmado con la clave del tenant no es de Entra:
    // no hay decoder de Cognito en este servicio.
    String token = firmar(azure, COGNITO_ISSUER, AZURE_AUDIENCE,
        Instant.now().plusSeconds(600), Map.of("token_use", "id"));

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaAudienceAjenaConFirmaValida() {
    String token = firmar(azure, AZURE_ISSUER, "otra-api", Instant.now().plusSeconds(600), Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaTokenExpirado() {
    String token = firmar(azure, AZURE_ISSUER, AZURE_AUDIENCE, Instant.now().minusSeconds(180), Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaTokenSinExpiracion() {
    String token = firmar(azure, AZURE_ISSUER, AZURE_AUDIENCE, null, Map.of());

    assertThrows(JwtException.class, () -> config.jwtDecoder().decode(token));
  }

  @Test
  void rechazaTokenMalformado() {
    JwtDecoder decoder = config.jwtDecoder();

    assertThrows(JwtException.class, () -> decoder.decode("no.es.un.jwt"));
  }

  @Test
  void crearDecoderNoConsultaJwks() {
    config.jwtDecoder();

    assertEquals(0, peticiones.get()); // construir no descarga ninguna clave
  }

  @Test
  void configuracionVaciaNoOmiteTenantNiAudience() {
    ReflectionTestUtils.setField(config, "tenantId", "");
    IllegalStateException sinTenant = assertThrows(IllegalStateException.class, () -> config.jwtDecoder());
    assertTrue(sinTenant.getMessage().contains("AZURE_TENANT_ID"));

    ReflectionTestUtils.setField(config, "tenantId", TENANT);
    ReflectionTestUtils.setField(config, "azureAudience", "");
    IllegalStateException sinAudience = assertThrows(IllegalStateException.class, () -> config.jwtDecoder());
    assertTrue(sinAudience.getMessage().contains("AZURE_AUDIENCE"));
  }

  @Test
  void produccionExigeTenantAudienceYOrigenes() throws Exception {
    Properties prod = new Properties();
    try (var entrada = getClass().getResourceAsStream("/application-prod.properties")) {
      prod.load(entrada);
    }
    assertEquals("${AZURE_TENANT_ID}", prod.getProperty("azure.tenant-id"));
    assertEquals("${AZURE_AUDIENCE}", prod.getProperty("azure.audience"));
    assertEquals("${CORS_ALLOWED_ORIGINS:http://localhost:4200}", prod.getProperty("cors.allowed-origins"));
  }
}
