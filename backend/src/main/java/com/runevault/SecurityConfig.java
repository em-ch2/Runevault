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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
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
   RequestQuotaLimiter limiter) throws Exception {
  boolean auth = env.acceptsProfiles(Profiles.of("auth"));
  boolean simulator = env.acceptsProfiles(Profiles.of("simulator"));
  if (env.acceptsProfiles(Profiles.of("prod")))
   throw new IllegalStateException("Production banking is not enabled");
  if (!auth && !env.acceptsProfiles(Profiles.of("dev"))) throw new IllegalStateException("Select dev or auth profile");
  if (simulator && (auth || !env.acceptsProfiles(Profiles.of("dev"))))
   throw new IllegalStateException("Simulator requires dev and cannot run with auth");
  if (simulator && env.getProperty("nessie.enabled", Boolean.class, false))
   throw new IllegalStateException("Hardware simulator supports local fake banking only; disable Nessie");
  if (!simulator && !env.acceptsProfiles(Profiles.of("test"))
    && !env.getProperty("nessie.enabled", Boolean.class, true))
   throw new IllegalStateException("Normal application profiles require Nessie sandbox banking");
  if (token.length() < 24 || (auth && token.startsWith("local-"))) throw new IllegalStateException("Set a strong bridge token (24+ characters)");
  http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  http.authorizeHttpRequests(a -> {
   a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll();
   a.requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
   a.requestMatchers(HttpMethod.GET, "/ws/transfers").permitAll();
   // Bridge requests have their own credential, checked by Gate before authorization.
   if (simulator) a.requestMatchers("/api/challenges", "/api/approvals").permitAll();
   a.requestMatchers("/api/device/authorize", "/api/device/transactions", "/api/device/transactions/*", "/api/device/pending", "/api/lore/*").permitAll();
   if (auth) {
    a.requestMatchers(HttpMethod.POST, "/api/transactions").hasAuthority("PERM_create:transactions");
    a.requestMatchers(HttpMethod.POST, "/api/transactions/*/authorize").hasAuthority("PERM_create:transactions");
    a.requestMatchers(HttpMethod.GET, "/api/transactions").hasAuthority("PERM_read:transactions");
    a.requestMatchers(HttpMethod.GET, "/api/transactions/approvals").hasAuthority("PERM_approve:transactions");
    a.requestMatchers(HttpMethod.POST, "/api/transactions/*/approve").hasAuthority("PERM_approve:transactions");
    a.requestMatchers(HttpMethod.POST, "/api/ws-ticket").hasAuthority("PERM_read:transactions");
    a.requestMatchers(HttpMethod.GET, "/api/metrics/pipeline").hasAuthority("PERM_read:transactions");
    a.requestMatchers(HttpMethod.GET, "/api/bank/balance").hasAuthority("PERM_read:transactions");
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
   var bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
   http.oauth2ResourceServer(o -> o
    .authenticationEntryPoint((request, response, exception) -> {
     if (request.getHeader("Authorization") != null) {
      int retry = limiter.retryAfter(RequestQuotaLimiter.Category.UNAUTHENTICATED, request.getRemoteAddr());
      if (retry != 0) {
       response.setHeader("Retry-After", Integer.toString(retry));
       response.sendError(429); return;
      }
     }
     bearerEntryPoint.commence(request, response, exception);
    })
    .jwt(j -> j.jwtAuthenticationConverter(converter)));
  }
  http.addFilterBefore(new Gate(token, limiter, simulator), UsernamePasswordAuthenticationFilter.class);
  http.addFilterAfter(new UserQuotaFilter(limiter), BearerTokenAuthenticationFilter.class);
  return http.build();
 }

 static class Gate extends OncePerRequestFilter {
  private final byte[] token;
  private final RequestQuotaLimiter limiter;
  private final boolean simulator;
  Gate(String token, RequestQuotaLimiter limiter, boolean simulator) {
   this.token = token.getBytes(StandardCharsets.UTF_8); this.limiter = limiter; this.simulator = simulator;
  }
  private boolean limited(HttpServletResponse response, RequestQuotaLimiter.Category category, String identity) throws IOException {
   int retry = limiter.retryAfter(category, identity);
   if (retry == 0) return false;
   response.setHeader("Retry-After", Integer.toString(retry));
   response.sendError(429);
   return true;
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
   String path = request.getRequestURI().substring(request.getContextPath().length());
   if (isHealthProbe(request)) { chain.doFilter(request,response); return; }
   boolean bridge = (simulator && (path.equals("/api/challenges") || path.equals("/api/approvals")))
     || path.startsWith("/api/device/") || path.startsWith("/api/lore/");
   if (bridge) {
    String supplied = request.getHeader("X-Bridge-Token");
    if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
     if (limited(response, RequestQuotaLimiter.Category.UNAUTHENTICATED, request.getRemoteAddr())) return;
     response.sendError(401); return;
    }
    RequestQuotaLimiter.Category category = path.equals("/api/device/authorize") || path.equals("/api/approvals")
     ? RequestQuotaLimiter.Category.BRIDGE_AUTHORIZATION
     : path.equals("/api/device/transactions") && request.getMethod().equals("POST") || path.equals("/api/challenges")
     ? RequestQuotaLimiter.Category.TRANSACTION_CREATION : RequestQuotaLimiter.Category.BRIDGE_OTHER;
    if (limited(response, category, "configured-bridge")) return;
   } else if (request.getHeader("Authorization") == null) {
    if (limited(response, RequestQuotaLimiter.Category.UNAUTHENTICATED, request.getRemoteAddr())) return;
   }
   chain.doFilter(request,response);
  }
 }

 static class UserQuotaFilter extends OncePerRequestFilter {
  private final RequestQuotaLimiter limiter;
  UserQuotaFilter(RequestQuotaLimiter limiter) { this.limiter = limiter; }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
   String path = request.getRequestURI().substring(request.getContextPath().length());
   if (isHealthProbe(request)) { chain.doFilter(request, response); return; }
   var authentication = SecurityContextHolder.getContext().getAuthentication();
   if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
     && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
    RequestQuotaLimiter.Category category = path.startsWith("/api/admin/")
     ? RequestQuotaLimiter.Category.ADMIN
     : path.equals("/api/ws-ticket") && request.getMethod().equals("POST")
     ? RequestQuotaLimiter.Category.WS_TICKET
     : (path.equals("/api/transactions") || path.equals("/api/transactions/approvals")
       || path.equals("/api/metrics/pipeline") || path.equals("/api/bank/balance")) && request.getMethod().equals("GET")
     ? RequestQuotaLimiter.Category.DASHBOARD_READ
     : (path.equals("/api/transactions") || path.startsWith("/api/transactions/") && path.endsWith("/approve"))
       && request.getMethod().equals("POST")
     ? RequestQuotaLimiter.Category.TRANSACTION_CREATION
     : path.startsWith("/api/transactions/") && path.endsWith("/authorize") && request.getMethod().equals("POST")
     ? RequestQuotaLimiter.Category.BRIDGE_AUTHORIZATION : null;
    // Issuance quota counts eligible ticket requests, not permission-denied attempts.
    if (category == RequestQuotaLimiter.Category.WS_TICKET
      && authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("PERM_read:transactions")))
     category = null;
    if (category != null) {
     int retry = limiter.retryAfter(category, "user:" + jwt.getSubject());
     if (retry != 0) {
      response.setHeader("Retry-After", Integer.toString(retry));
      response.sendError(429); return;
     }
    }
   }
   chain.doFilter(request, response);
  }
 }

 private static boolean isHealthProbe(HttpServletRequest request) {
  if (!request.getMethod().equals("GET")) return false;
  String path = request.getRequestURI().substring(request.getContextPath().length());
  return path.equals("/api/health") || path.equals("/actuator/health/liveness")
   || path.equals("/actuator/health/readiness");
 }
}
