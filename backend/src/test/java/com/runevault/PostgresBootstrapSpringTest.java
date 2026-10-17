package com.runevault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "runevault.pico-key=test-pico-key-for-postgres-bootstrap",
 "spring.flyway.enabled=true",
 "spring.jpa.hibernate.ddl-auto=validate",
 "spring.sql.init.mode=never",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.security.oauth2.resourceserver.jwt.audiences=https://api.runevault"})
@ActiveProfiles({"auth", "test"})
class PostgresBootstrapSpringTest {
 @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

 @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
  properties.add("spring.datasource.url", postgres::getJdbcUrl);
  properties.add("spring.datasource.username", postgres::getUsername);
  properties.add("spring.datasource.password", postgres::getPassword);
 }

 @MockBean JwtDecoder decoder;
 @Autowired JdbcTemplate jdbc;

 @Test void emptyPostgresMigratesBeforeHibernateValidation() {
  assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
  assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
 }
}
