package com.runevault;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Fails cloud startup without ever including configured values in errors. */
public class CloudDeploymentGuard implements EnvironmentPostProcessor, Ordered {
 private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

 @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

 @Override public void postProcessEnvironment(org.springframework.core.env.ConfigurableEnvironment env,
     SpringApplication application) {
  if (Arrays.asList(env.getActiveProfiles()).contains("cloud")) validate(env);
 }

 static void validate(Environment env) {
  var profiles = Arrays.asList(env.getActiveProfiles());
  if (!profiles.contains("auth") || profiles.contains("dev") || profiles.contains("test")
      || profiles.contains("simulator") || profiles.contains("prod"))
   fail("SPRING_PROFILES_ACTIVE must be auth,cloud only");
  if (!"true".equals(env.getProperty("nessie.enabled"))) fail("NESSIE_ENABLED must be true");
  if (!"validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")))
   fail("Cloud schema must be validated, not modified automatically");
  if (!"never".equals(env.getProperty("spring.sql.init.mode")))
   fail("Cloud SQL initialization must be disabled");
  String domain = required(env, "RUNEVAULT_DOMAIN", 4);
  if (!domain.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}"))
   fail("RUNEVAULT_DOMAIN must be a public hostname");
  if (!required(env, "RUNEVAULT_WS_ALLOWED_ORIGINS", 10).equals("https://" + domain))
   fail("RUNEVAULT_WS_ALLOWED_ORIGINS must match the HTTPS frontend origin");
  positiveLong(env, "RUNEVAULT_WS_TICKET_SECONDS");
  positiveLong(env, "RUNEVAULT_WS_MAX_TICKETS");
  positiveLong(env, "RUNEVAULT_WS_MAX_CONNECTIONS_PER_USER");
  positiveLong(env, "RUNEVAULT_WS_TICKET_ISSUANCE_PER_WINDOW");

  String issuer = required(env, "AUTH0_ISSUER_URI", 12);
  if (!issuer.startsWith("https://") || !issuer.endsWith("/")) fail("AUTH0_ISSUER_URI must be an HTTPS issuer ending in /");
  if (!"https://api.runevault".equals(required(env, "AUTH0_AUDIENCE", 1)))
   fail("AUTH0_AUDIENCE must remain the registered Runevault API audience");
  required(env, "SPRING_DATASOURCE_USERNAME", 1);
  required(env, "SPRING_DATASOURCE_PASSWORD", 24);
  String url = required(env, "SPRING_DATASOURCE_URL", 30);
  validateJdbcUrl(url);
  required(env, "RUNEVAULT_BRIDGE_TOKEN", 32);
  required(env, "RUNEVAULT_DEVICE_SECRET", 32);
  required(env, "RUNEVAULT_PICO_KEY", 32);
  required(env, "RUNEVAULT_PICO_DEVICE_ID", 1);
  required(env, "GEMINI_API_KEY", 16);
  required(env, "NESSIE_API_KEY", 16);
  if (!UUID.matcher(required(env, "NESSIE_SOURCE_ACCOUNT_ID", 36)).matches())
   fail("NESSIE_SOURCE_ACCOUNT_ID must be a UUID");
  positiveLong(env, "RUNEVAULT_MAX_AMOUNT_MINOR");
  positiveLong(env, "RUNEVAULT_MAX_INCORRECT_GLYPH_ATTEMPTS");
  for (String name : new String[] {"RUNEVAULT_RATE_WINDOW_SECONDS", "RUNEVAULT_RATE_MAX_ENTRIES",
      "RUNEVAULT_DASHBOARD_READS_PER_WINDOW", "RUNEVAULT_TRANSACTION_CREATION_PER_WINDOW",
      "RUNEVAULT_BRIDGE_AUTHORIZATIONS_PER_WINDOW", "RUNEVAULT_BRIDGE_OTHER_PER_WINDOW",
      "RUNEVAULT_ADMIN_OPERATIONS_PER_WINDOW", "RUNEVAULT_UNAUTHENTICATED_PER_WINDOW"}) positiveLong(env, name);
  String destinations = required(env, "RUNEVAULT_ALLOWED_DESTINATIONS", 36);
  if (Arrays.stream(destinations.split(",", -1)).anyMatch(d -> !UUID.matcher(d.trim()).matches()))
   fail("RUNEVAULT_ALLOWED_DESTINATIONS must contain Nessie account UUIDs");
  decimalRange(env, "RUNEVAULT_MIN_TRANSLATION_CONFIDENCE", 0, 1);
  decimalRange(env, "RUNEVAULT_MAX_RISK_SCORE", 0, 100);
 }

 private static String required(Environment env, String name, int minLength) {
  String value = env.getProperty(name);
  if (value == null || value.isBlank() || value.length() < minLength) fail("Missing or weak " + name);
  String lower = value.toLowerCase(Locale.ROOT);
  for (String placeholder : new String[] {"replace_", "your-", "your_", "changeme", "change-me",
      "placeholder", "local-", "example.com", "acct-999", "poneglyph-dev-key"})
   if (lower.contains(placeholder)) fail("Placeholder or development value for " + name);
  return value;
 }

 private static void validateJdbcUrl(String url) {
  try {
   if (!url.startsWith("jdbc:postgresql://")) fail("TIGER_JDBC_URL must use PostgreSQL JDBC");
   URI uri = URI.create(url.substring(5));
   if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2 || uri.getUserInfo() != null)
    fail("TIGER_JDBC_URL requires host and database without embedded credentials");
   String query = uri.getRawQuery();
   if (query == null) fail("TIGER_JDBC_URL requires sslmode=verify-full");
   boolean verifyFull = false;
   for (String part : query.split("&")) {
    String[] pair = part.split("=", 2);
    String key = pair[0].toLowerCase(Locale.ROOT);
    if (key.equals("sslmode")) {
     if (verifyFull || pair.length != 2 || !pair[1].equals("verify-full")) fail("TIGER_JDBC_URL requires exactly one sslmode=verify-full");
     verifyFull = true;
    }
    if (key.equals("password") || key.equals("user") || key.equals("sslfactory") || key.equals("sslhostnameverifier"))
     fail("TIGER_JDBC_URL contains a forbidden parameter");
    if (key.equals("sslrootcert")) {
     if (pair.length != 2 || !pair[1].startsWith("/run/tiger-certs/"))
      fail("TIGER_JDBC_URL CA must be under /run/tiger-certs");
     if (!Files.isReadable(Path.of(pair[1]))) fail("Tiger CA file is not readable");
    }
   }
   if (!verifyFull) fail("TIGER_JDBC_URL requires sslmode=verify-full");
  } catch (IllegalArgumentException e) {
   fail("Invalid TIGER_JDBC_URL");
  }
 }

 private static void positiveLong(Environment env, String name) {
  try { if (Long.parseLong(required(env, name, 1)) <= 0) fail("Invalid " + name); }
  catch (NumberFormatException e) { fail("Invalid " + name); }
 }

 private static void decimalRange(Environment env, String name, double min, double max) {
  try {
   double value = Double.parseDouble(required(env, name, 1));
   if (!Double.isFinite(value) || value < min || value > max) fail("Invalid " + name);
  } catch (NumberFormatException e) { fail("Invalid " + name); }
 }

 private static void fail(String message) { throw new IllegalStateException(message); }
}
