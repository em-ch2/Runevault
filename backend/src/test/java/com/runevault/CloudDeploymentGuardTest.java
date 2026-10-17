package com.runevault;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CloudDeploymentGuardTest {
 private MockEnvironment valid() {
  MockEnvironment env = new MockEnvironment();
  env.setActiveProfiles("auth", "cloud");
  env.setProperty("nessie.enabled", "true");
  env.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
  env.setProperty("spring.sql.init.mode", "never");
  env.setProperty("RUNEVAULT_DOMAIN", "runevault.testdomain.com");
  env.setProperty("RUNEVAULT_WS_ALLOWED_ORIGINS", "https://runevault.testdomain.com");
  env.setProperty("RUNEVAULT_WS_TICKET_SECONDS", "30");
  env.setProperty("RUNEVAULT_WS_MAX_TICKETS", "10000");
  env.setProperty("RUNEVAULT_WS_MAX_CONNECTIONS_PER_USER", "3");
  env.setProperty("RUNEVAULT_WS_TICKET_ISSUANCE_PER_WINDOW", "30");
  env.setProperty("AUTH0_ISSUER_URI", "https://tenant.auth0.com/");
  env.setProperty("AUTH0_AUDIENCE", "https://api.runevault");
  env.setProperty("SPRING_DATASOURCE_USERNAME", "runevault");
  env.setProperty("SPRING_DATASOURCE_PASSWORD", "a-long-private-database-password");
  env.setProperty("SPRING_DATASOURCE_URL", "jdbc:postgresql://db.tigerdata.com:5432/runevault?sslmode=verify-full");
  env.setProperty("RUNEVAULT_BRIDGE_TOKEN", "private-random-bridge-token-with-entropy");
  env.setProperty("RUNEVAULT_DEVICE_SECRET", "private-random-device-secret-with-entropy");
  env.setProperty("RUNEVAULT_PICO_KEY", "private-random-pico-key-with-entropy");
  env.setProperty("RUNEVAULT_PICO_DEVICE_ID", "slab-01");
  env.setProperty("GEMINI_API_KEY", "private-gemini-key");
  env.setProperty("NESSIE_API_KEY", "private-nessie-key");
  env.setProperty("NESSIE_SOURCE_ACCOUNT_ID", "11111111-1111-1111-1111-111111111111");
  env.setProperty("RUNEVAULT_MAX_AMOUNT_MINOR", "100000");
  env.setProperty("RUNEVAULT_ALLOWED_DESTINATIONS", "22222222-2222-2222-2222-222222222222");
  env.setProperty("RUNEVAULT_MAX_INCORRECT_GLYPH_ATTEMPTS", "5");
  env.setProperty("RUNEVAULT_MIN_TRANSLATION_CONFIDENCE", "0.8");
  env.setProperty("RUNEVAULT_MAX_RISK_SCORE", "30");
  for (String name : new String[] {"RUNEVAULT_RATE_WINDOW_SECONDS", "RUNEVAULT_RATE_MAX_ENTRIES",
      "RUNEVAULT_DASHBOARD_READS_PER_WINDOW", "RUNEVAULT_TRANSACTION_CREATION_PER_WINDOW",
      "RUNEVAULT_BRIDGE_AUTHORIZATIONS_PER_WINDOW", "RUNEVAULT_BRIDGE_OTHER_PER_WINDOW",
      "RUNEVAULT_ADMIN_OPERATIONS_PER_WINDOW", "RUNEVAULT_UNAUTHENTICATED_PER_WINDOW"})
   env.setProperty(name, "60");
  return env;
 }

 @Test void acceptsCompleteSandboxConfiguration() {
  assertDoesNotThrow(() -> CloudDeploymentGuard.validate(valid()));
 }

 @Test void rejectsUnsafeDatabaseTransport() {
  MockEnvironment env = valid();
  env.setProperty("SPRING_DATASOURCE_URL",
   "jdbc:postgresql://db.tigerdata.com:5432/runevault?sslmode=require");
  assertThrows(IllegalStateException.class, () -> CloudDeploymentGuard.validate(env));
 }

 @Test void rejectsPlaceholderAndSimulatorProfile() {
  MockEnvironment env = valid();
  env.setProperty("RUNEVAULT_PICO_KEY", "poneglyph-dev-key-change-me-0001");
  assertThrows(IllegalStateException.class, () -> CloudDeploymentGuard.validate(env));
  MockEnvironment simulator = valid();
  simulator.setActiveProfiles("auth", "cloud", "simulator");
  assertThrows(IllegalStateException.class, () -> CloudDeploymentGuard.validate(simulator));
 }
}
