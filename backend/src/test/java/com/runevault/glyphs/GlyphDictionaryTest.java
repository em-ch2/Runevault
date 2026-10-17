package com.runevault.glyphs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GlyphDictionaryTest {
 private final GlyphDictionary dictionary=new GlyphDictionary();
 @Test void hardwareOrderingAndLegacyAreSupported() {
  assertEquals(dictionary.decode("ABC"),dictionary.decode("0-1-2"));
  assertEquals(java.util.List.of(dictionary.decode("0").getFirst(),dictionary.decode("2").getFirst(),dictionary.decode("0").getFirst()),dictionary.decode("0-2-0"));
  assertEquals(64,dictionary.decode("0-".repeat(63)+"0").size());
 }
 @Test void rejectsMalformedSequences() {
  for(String bad:new String[]{"","012","0-3","0--1","-0","0-","A-0","0 1","0-".repeat(64)+"0"})
   assertThrows(IllegalArgumentException.class,()->dictionary.decode(bad));
  assertThrows(IllegalArgumentException.class,()->dictionary.decode(null));
 }
}
