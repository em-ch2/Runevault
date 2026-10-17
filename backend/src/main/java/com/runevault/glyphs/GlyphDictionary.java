package com.runevault.glyphs;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class GlyphDictionary {
    public static final String VERSION = "lore-v2";
    private static final Map<Character, String> MEANINGS = Map.of(
        '0', "In the old kingdom, the moon gate marked a safe departure.",
        '1', "The river rune recalled a promise carried between distant halls.",
        '2', "The ember seal symbolized a gift entrusted to a guardian.",
        'A', "In the old kingdom, the moon gate marked a safe departure.",
        'B', "The river rune recalled a promise carried between distant halls.",
        'C', "The ember seal symbolized a gift entrusted to a guardian.",
        'D', "The stone crown remembered an oath beneath the mountain."
    );
    public List<String> decode(String sequence) {
        if (sequence == null || !(sequence.matches("[0-2](-[0-2]){0,63}") || sequence.matches("[A-D]{1,16}"))) {
            throw new IllegalArgumentException("Invalid hardware or legacy glyph sequence");
        }
        return sequence.chars().filter(c -> c != '-').mapToObj(c -> MEANINGS.get((char)c)).toList();
    }
}
