package com.runevault;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulationModeConfigTest {
 @Test void simulatorCannotRunWithAuthOrNessie() {
  var auth = new MockEnvironment();
  auth.setActiveProfiles("auth", "simulator");
  assertThrows(IllegalStateException.class, () -> new SecurityConfig().security(null, auth,
   "test-bridge-token-with-enough-characters", null));

  var nessie = new MockEnvironment();
  nessie.setActiveProfiles("dev", "simulator");
  nessie.setProperty("nessie.enabled", "true");
  assertThrows(IllegalStateException.class, () -> new SecurityConfig().security(null, nessie,
   "test-bridge-token-with-enough-characters", null));
 }
}
