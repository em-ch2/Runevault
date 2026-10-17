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
}
