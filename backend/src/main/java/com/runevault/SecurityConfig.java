package com.runevault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
 @Bean
 SecurityFilterChain security(HttpSecurity http, Environment env,
   @Value("${runevault.bridge-token}") String token,
   @Value("${runevault.requests-per-minute}") int limit) throws Exception {
  boolean auth = env.acceptsProfiles(Profiles.of("auth"));
  if (!auth && !env.acceptsProfiles(Profiles.of("dev"))) throw new IllegalStateException("Select dev or auth profile");
  if (token.length() < 24 || (auth && token.startsWith("local-"))) throw new IllegalStateException("Set a strong bridge token (24+ characters)");
  http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  http.authorizeHttpRequests(a -> {
   a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll();
   a.requestMatchers("/api/health").permitAll();
   // Bridge requests have their own credential, checked by Gate before authorization.
   a.requestMatchers("/api/challenges", "/api/approvals", "/api/device/authorize", "/api/device/transactions", "/api/device/transactions/*", "/api/lore/*").permitAll();
   if (auth) {
    a.requestMatchers(HttpMethod.POST, "/api/transactions").hasAuthority("PERM_create:transactions");
    a.requestMatchers(HttpMethod.GET, "/api/transactions").hasAuthority("PERM_read:transactions");
   }
   if (auth) a.requestMatchers("/api/admin/**").hasAuthority("PERM_admin:platform").anyRequest().denyAll();
   else a.anyRequest().denyAll();
  });
  if (auth) {
   var permissions = new JwtGrantedAuthoritiesConverter();
   permissions.setAuthoritiesClaimName("permissions");
   permissions.setAuthorityPrefix("PERM_");
   var converter = new JwtAuthenticationConverter();
   converter.setJwtGrantedAuthoritiesConverter(permissions);
   http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)));
  }
  http.addFilterBefore(new Gate(token, limit), UsernamePasswordAuthenticationFilter.class);
  return http.build();
 }

 static class Gate extends OncePerRequestFilter {
  private final byte[] token;
  private final int limit;
  private long window = System.nanoTime();
  private int count;
  Gate(String token, int limit) {
   if (limit < 1) throw new IllegalArgumentException("Request limit must be positive");
   this.token = token.getBytes(StandardCharsets.UTF_8); this.limit = limit;
  }
  private synchronized boolean allow() {
   long now = System.nanoTime();
   if (now - window >= 60_000_000_000L) { window = now; count = 0; }
   return ++count <= limit;
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
   String path = request.getRequestURI().substring(request.getContextPath().length());
   if (path.equals("/api/health")) { chain.doFilter(request,response); return; }
   if (!allow()) { response.setHeader("Retry-After","60"); response.sendError(429); return; }
   if (path.equals("/api/challenges") || path.equals("/api/approvals")
     || path.equals("/api/device/authorize") || path.equals("/api/device/transactions")
     || (request.getMethod().equals("GET") && path.matches("/api/device/transactions/[^/]+"))
     || (request.getMethod().equals("GET") && path.matches("/api/lore/[^/]+"))) {
    String supplied = request.getHeader("X-Bridge-Token");
    if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) { response.sendError(401); return; }
   }
   chain.doFilter(request,response);
  }
 }
}