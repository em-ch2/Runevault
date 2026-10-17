package com.runevault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.rate-limit.unauthenticated=20",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault",
 "spring.datasource.url=jdbc:h2:mem:operational-health;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class OperationalHealthHttpTest {
 @Autowired MockMvc mvc;
 @MockBean JwtDecoder decoder;
 @MockBean(name = "dbHealthContributor") HealthIndicator databaseHealth;
 @Autowired org.springframework.boot.actuate.health.HealthContributorRegistry contributors;
 @Autowired org.springframework.core.env.Environment environment;
 @Autowired org.springframework.boot.actuate.health.HealthEndpointGroups groups;

 @Test void onlyExactProbeRoutesArePublicAndUnmetered() throws Exception {
  when(databaseHealth.getHealth(false)).thenReturn(Health.up().build());
  mvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/device/authorize")).andExpect(status().isUnauthorized());
  for (int i = 0; i < 3; i++) {
   mvc.perform(get("/api/health")).andExpect(status().isOk());
   mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
    .andExpect(jsonPath("$.status").value("UP"))
    .andExpect(jsonPath("$.components").doesNotExist());
   mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
    .andExpect(jsonPath("$.status").value("UP"))
    .andExpect(jsonPath("$.components").doesNotExist());
  }
  mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
  mvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());
  mvc.perform(get("/actuator/health")).andExpect(status().is4xxClientError());
  mvc.perform(post("/actuator/health/liveness")).andExpect(status().is4xxClientError());
 }

 @Test void databaseFailureMakesReadinessDownButLivenessStaysUp() throws Exception {
  org.junit.jupiter.api.Assertions.assertSame(databaseHealth, contributors.getContributor("db"));
  org.junit.jupiter.api.Assertions.assertEquals("readinessState,db",
   environment.getProperty("management.endpoint.health.group.readiness.include"));
  org.junit.jupiter.api.Assertions.assertTrue(groups.get("readiness").isMember("db"));
  when(databaseHealth.getHealth(false)).thenReturn(Health.down().build());
  var readiness = mvc.perform(get("/actuator/health/readiness")).andReturn();
  verify(databaseHealth, atLeastOnce()).getHealth(false);
  org.junit.jupiter.api.Assertions.assertEquals(503, readiness.getResponse().getStatus());
  org.junit.jupiter.api.Assertions.assertTrue(readiness.getResponse().getContentAsString().contains("DOWN"));
  clearInvocations(databaseHealth);
  mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
   .andExpect(jsonPath("$.status").value("UP"));
  verify(databaseHealth, never()).getHealth(false);
  mvc.perform(get("/api/health")).andExpect(status().isOk());
 }
}
