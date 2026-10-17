package com.runevault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"runevault.bridge-token=test-bridge-token-with-enough-characters", "runevault.requests-per-minute=1000"})
@AutoConfigureMockMvc
class SecurityHttpTest {
 @Autowired MockMvc mvc;
 @Test void healthIsPublic() throws Exception {
  mvc.perform(get("/api/health")).andExpect(status().isOk());
 }
 @Test void bridgeCredentialRequired() throws Exception {
  mvc.perform(post("/api/challenges").contentType("application/json").content("{}"))
   .andExpect(status().isUnauthorized());
 }
 @Test void malformedIntentIsBadRequest() throws Exception {
  mvc.perform(post("/api/challenges").header("X-Bridge-Token","test-bridge-token-with-enough-characters")
   .contentType("application/json").content("{}"))
   .andExpect(status().isBadRequest());
 }
 @Test void bridgeCanCreateChallenge() throws Exception {
  mvc.perform(post("/api/challenges").header("X-Bridge-Token","test-bridge-token-with-enough-characters")
   .contentType("application/json").content("""
    {"deviceId":"pico-demo-1","amountMinor":2500,"destination":"harbor","glyphs":"ABCD"}
    """))
   .andExpect(status().isOk()).andExpect(jsonPath("challengeId").isNotEmpty());
 }
 @Test void bridgeCannotAccessAdmin() throws Exception {
  mvc.perform(get("/api/admin/status").header("X-Bridge-Token","test-bridge-token-with-enough-characters"))
   .andExpect(status().isForbidden());
 }
}
