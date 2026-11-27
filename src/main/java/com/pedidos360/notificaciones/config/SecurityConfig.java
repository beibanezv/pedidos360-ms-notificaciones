package com.pedidos360.notificaciones.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * El correo directo es una accion de administracion: el authorizer del Gateway
 * valida identidad, no convierte a un comprador en administrador, asi que el
 * backend exige ROLE_Admin de Entra. Cognito no tiene decoder aqui: su token
 * muere con 401 y un comprador de Entra con 403.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final String JWKS_DEFAULT = "https://login.microsoftonline.com/";

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:https://login.microsoftonline.com/}")
  private String issuerUri;

  @Value("${azure.tenant-id:e5372bf0-c5e3-4286-887c-79069f209c1f}")
  private String tenantId;

  @Value("${azure.audience:api://446c57cb-aba2-4b7a-ab01-6f2e6af7d35c}")
  private String azureAudience;

  @Value("${cors.allowed-origins:http://localhost:4200}")
  private String allowedOrigins;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/notificaciones/enviar").hasRole("Admin")
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

    return http.build();
  }

  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    // Azure AD roles vienen en "roles": ["Cliente","Admin"]. Sin roles no hay
    // autoridad, asi que un token valido sin rol permitido acaba en 403.
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      List<String> roles = jwt.getClaimAsStringList("roles");
      if (roles == null) return List.of();
      return roles.stream()
          .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
          .toList();
    });
    return converter;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
    config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  @Bean
  public JwtDecoder jwtDecoder() {
    String tenant = obligatorio(tenantId, "AZURE_TENANT_ID");
    String audiencia = obligatorio(azureAudience, "AZURE_AUDIENCE");
    // Issuer v1 exacto del tenant y JWKS v1: construir no consulta la red.
    String emisor = "https://sts.windows.net/" + tenant + "/";
    String base = (issuerUri == null || issuerUri.isBlank()) ? JWKS_DEFAULT : issuerUri.trim();
    if (!base.endsWith("/")) {
      base += "/";
    }
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(base + tenant + "/discovery/keys").build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(emisor),
        new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audiencia)),
        new JwtClaimValidator<Object>("exp", exp -> exp != null)));
    return decoder;
  }

  private static String obligatorio(String valor, String nombre) {
    if (valor == null || valor.isBlank()) {
      throw new IllegalStateException("Configuración JWT obligatoria vacía: " + nombre);
    }
    return valor.trim();
  }
}
