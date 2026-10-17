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
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
 "runevault.bridge-token=test-bridge-token-with-enough-characters",
 "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test/",
 "spring.datasource.url=jdbc:h2:mem:admin-audit-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles({"auth", "test"})
class AdminAuditHttpTest {
 @Autowired MockMvc mvc;
 @Autowired AuditEventRepository auditEvents;
 @MockBean JwtDecoder jwtDecoder;

 private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(String path) {
  return get(path).with(jwt().authorities(new SimpleGrantedAuthority("PERM_admin:platform")));
 }

 @Test void onlyAdminCanReadPagedNewestFirst() throws Exception {
  auditEvents.deleteAll();
  auditEvents.saveAndFlush(new AuditEvent("slab-01", "first", AuditEvent.Outcome.REJECTED, AuditEvent.Reason.INVALID_SIGNATURE));
  auditEvents.saveAndFlush(new AuditEvent("slab-01", "second", AuditEvent.Outcome.APPROVED, AuditEvent.Reason.APPROVED));
  mvc.perform(get("/api/admin/audit-events")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/audit-events").with(jwt())).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/audit-events").header("X-Bridge-Token", "test-bridge-token-with-enough-characters"))
   .andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/audit-events?page=0&size=1")
    .with(jwt().authorities(new SimpleGrantedAuthority("PERM_admin:platform"))))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
   .andExpect(jsonPath("$.content[0].transactionId").value("second"));
  mvc.perform(get("/api/admin/audit-events?page=1&size=1")
    .with(jwt().authorities(new SimpleGrantedAuthority("PERM_admin:platform"))))
   .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].transactionId").value("first"));
  mvc.perform(get("/api/admin/audit-events?size=101")
    .with(jwt().authorities(new SimpleGrantedAuthority("PERM_admin:platform"))))
   .andExpect(status().isBadRequest());
 }

 @Test void filtersAreCombinedBeforePaginationAndBoundariesAreInclusive() throws Exception {
  auditEvents.deleteAll();
  var first = auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.DEVICE_AUTHORIZATION,
   "slab-01", "tx-one", AuditEvent.Outcome.REJECTED, AuditEvent.Reason.INVALID_SIGNATURE));
  var second = auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.DEVICE_AUTHORIZATION,
   "slab-01", "tx-two", AuditEvent.Outcome.REJECTED, AuditEvent.Reason.COUNTER_REPLAY));
  auditEvents.saveAndFlush(new AuditEvent(AuditEvent.Type.EXECUTION_CLAIM,
   "slab-02", "tx-three", AuditEvent.Outcome.APPROVED, AuditEvent.Reason.EXECUTION_CLAIMED));
  Instant boundary = auditEvents.findById(first.getId()).orElseThrow().getTimestamp();
  String at = boundary.toString();
  for (String filter : new String[] {"deviceId=slab-01", "transactionId=tx-one",
    "eventType=DEVICE_AUTHORIZATION", "outcome=REJECTED", "reason=INVALID_SIGNATURE"}) {
   int expected = filter.startsWith("transactionId") || filter.startsWith("reason") ? 1 : 2;
   mvc.perform(admin("/api/admin/audit-events?" + filter))
    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(expected));
  }
  mvc.perform(admin("/api/admin/audit-events?deviceId=slab-01&outcome=REJECTED&reason=COUNTER_REPLAY&size=1"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
   .andExpect(jsonPath("$.content[0].transactionId").value("tx-two"));
  mvc.perform(admin("/api/admin/audit-events?deviceId=slab-01&size=1&page=1"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
   .andExpect(jsonPath("$.content[0].transactionId").value("tx-one"));
  mvc.perform(admin("/api/admin/audit-events").param("from", at).param("to", at))
   .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].transactionId").value("tx-one"));
  mvc.perform(admin("/api/admin/audit-events").param("from", "2099-01-01T00:00:00Z"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  assertEquals(AuditEvent.Reason.COUNTER_REPLAY, auditEvents.findById(second.getId()).orElseThrow().getReason());
 }

 @Test void invalidFiltersReturnBadRequestAndAccessControlStillApplies() throws Exception {
  for (String filter : new String[] {"eventType=BAD", "outcome=BAD", "reason=BAD",
   "from=2026-01-01T00:00:00", "to=not-a-date",
   "from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z"}) {
   mvc.perform(admin("/api/admin/audit-events?" + filter)).andExpect(status().isBadRequest());
  }
  mvc.perform(get("/api/admin/audit-events?deviceId=slab-01"))
   .andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/audit-events?deviceId=slab-01").with(jwt()))
   .andExpect(status().isForbidden());
 }
}
