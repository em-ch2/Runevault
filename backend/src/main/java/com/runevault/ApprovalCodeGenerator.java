package com.runevault;

import java.security.SecureRandom;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

// The approval code must be unguessable, so it comes from a CSPRNG rather than a language model.
@Component
public class ApprovalCodeGenerator {
 static final int LENGTH = 6;
 static final int GLYPH_COUNT = 3;

 private final SecureRandom random = new SecureRandom();

 public String generate() {
  StringJoiner glyphs = new StringJoiner("-");
  for (int i = 0; i < LENGTH; i++) glyphs.add(Integer.toString(random.nextInt(GLYPH_COUNT)));
  return glyphs.toString();
 }
}
