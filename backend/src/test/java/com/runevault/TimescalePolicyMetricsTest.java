package com.runevault;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=test-pico-key-for-timescale-metrics",
 "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
 "spring.sql.init.mode=never",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class TimescalePolicyMetricsTest {
 @Container static PostgreSQLContainer<?> timescale = new PostgreSQLContainer<>(
  DockerImageName.parse("timescale/timescaledb:latest-pg17").asCompatibleSubstituteFor("postgres"))
   .withEnv("NO_TS_TUNE", "true").withInitScript("timescale-init.sql");

 @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
  properties.add("spring.datasource.url", timescale::getJdbcUrl);
  properties.add("spring.datasource.username", timescale::getUsername);
  properties.add("spring.datasource.password", timescale::getPassword);
 }

 @MockBean JwtDecoder decoder;
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;

 @Test void continuousAggregateCountsGeminiDecisionsOnly() throws Exception {
  Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
  String transferId = UUID.randomUUID().toString();
  for (String[] event : new String[][] {
   {"PENDING", "PENDING"}, {"VERIFIED", "APPROVED"},
   {"GEMINI", "APPROVED"}, {"EXECUTED", "COMPLETED"}
  }) jdbc.update("""
    INSERT INTO pipeline_events (event_id, occurred_at, transaction_id, stage, outcome)
    VALUES (?, ?, ?, ?, ?)
    """, UUID.randomUUID(), Timestamp.from(minute), transferId, event[0], event[1]);
  jdbc.update("""
    INSERT INTO pipeline_events (event_id, occurred_at, transaction_id, stage, outcome)
    VALUES (?, ?, ?, 'GEMINI', 'REJECTED')
    """, UUID.randomUUID(), Timestamp.from(minute), UUID.randomUUID().toString());
  mvc.perform(get("/api/metrics/policy-decisions?minutes=5")
   .with(jwt().authorities(new SimpleGrantedAuthority("PERM_read:audit"))))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.source").value("timescale-continuous-aggregate"))
   .andExpect(jsonPath("$.approved").value(1)).andExpect(jsonPath("$.blocked").value(1))
   .andExpect(jsonPath("$.minutes", org.hamcrest.Matchers.hasSize(5)));
 }
}
