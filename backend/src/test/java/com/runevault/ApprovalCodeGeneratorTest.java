package com.runevault;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalCodeGeneratorTest {
 private final ApprovalCodeGenerator generator = new ApprovalCodeGenerator();

 @Test void returnsSixDashSeparatedGlyphs() {
  for (int i = 0; i < 200; i++) assertTrue(generator.generate().matches("[0-2](-[0-2]){5}"));
 }

 @Test void codesVaryAcrossRequests() {
  Set<String> codes = new HashSet<>();
  for (int i = 0; i < 50; i++) codes.add(generator.generate());
  // 729 possible codes; 50 draws landing on very few distinct values would mean the source is not random.
  assertTrue(codes.size() > 30, "expected varied codes, got " + codes.size());
 }
}
