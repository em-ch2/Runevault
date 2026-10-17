# Signing contract v1 (A + B)

POST /api/challenges: {"deviceId":"pico-demo-1","amountMinor":2500,"destination":"demo-harbor","glyphs":"ABCD"}
Returns challengeId, expiresAt (Unix seconds), intent and signingText. Challenge expires in 120 seconds.

HMAC-SHA256 over UTF-8 text with exactly LF separators, no trailing newline:

```
runevault-v1
<challengeId>
<deviceId>
<amountMinor>
USD
<destination>
<glyphs>
<expiresAt>
```

POST /api/approvals: {"challengeId":"...","signature":"lowercase 64-character hex"}

Backend uses the stored intent, constant-time signature comparison and atomic challenge consumption. In-memory state is local only; persistence is required before multiple replicas. Invalid signatures do not consume challenges.

USB proposal: one JSON object per LF-terminated line, maximum 4096 bytes. Host sends type=challenge plus challenge fields; device returns type=approval plus challengeId and signature. Device reconstructs canonical text from validated fields and requires the physical glyph sequence. It must not sign arbitrary host-provided bytes.

No trusted device display means the user cannot independently verify amount/destination. USB-connected means offline signing token, not fully air-gapped. Real bridge holds no signing key. simulator.mjs is test-only and deliberately uses a public demo key.

Proposed Gemini schema: {"lore":"string","riskLevel":"LOW|MEDIUM|HIGH","reasons":["string"]}. Validate output; backend rules control authorization. The unrelated empathy schema from the original brief is not applicable.
