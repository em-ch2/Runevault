package com.runevault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.datasource.url=jdbc:h2:mem:revocation-admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class DeviceRevocationHttpTest {
 @Autowired MockMvc mvc;
 @Autowired DeviceStateRepository devices;
 @Autowired AuditEventRepository auditEvents;
 @MockBean JwtDecoder decoder;

 @Test void onlyAdminCanRevokeAndRepeatedCallsKeepEvidence() throws Exception {
  auditEvents.deleteAll();
  devices.deleteAll();
  var device = new DeviceState("slab-01");
  device.consumeCounter(17);
  devices.saveAndFlush(device);
  String path = "/api/admin/devices/slab-01/revoke";
  mvc.perform(post(path)).andExpect(status().isUnauthorized());
  mvc.perform(post(path).with(jwt())).andExpect(status().isForbidden());
  mvc.perform(post(path).header("X-Bridge-Token", "test-bridge-token-with-enough-characters"))
   .andExpect(status().isUnauthorized());
  var admin = jwt().jwt(jwt -> jwt.subject("auth0|administrator"))
   .authorities(new SimpleGrantedAuthority("PERM_admin:platform"));
  String first = mvc.perform(post(path).with(admin)).andExpect(status().isOk())
   .andExpect(jsonPath("revoked").value(true)).andExpect(jsonPath("revokedAt").isNotEmpty())
   .andReturn().getResponse().getContentAsString();
  String second = mvc.perform(post(path).with(admin)).andExpect(status().isOk())
   .andReturn().getResponse().getContentAsString();
  assertEquals(first, second);
  assertEquals(17, devices.findById("slab-01").orElseThrow().getLastCounter());
  assertEquals(1, auditEvents.count());
  var event = auditEvents.findAll().get(0);
  assertEquals(AuditEvent.Type.DEVICE_REVOCATION, event.getEventType());
  assertEquals(AuditEvent.Reason.REVOKED_BY_ADMIN, event.getReason());
  assertEquals("auth0|administrator", event.getActorSubject());
  mvc.perform(post("/api/admin/devices/unknown/revoke").with(admin)).andExpect(status().isNotFound());
 }
}
