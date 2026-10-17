package com.runevault.glyphs;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GlyphDictionary {

    public static final String VERSION = "v1";

    // Temporary demo facts; replace when the team finalizes the lore.
    private static final Map<Character, String> MEANINGS = Map.of(
        'A', "Octopuses have three hearts.",
        'B', "Venus takes longer to rotate once than to orbit the Sun.",
        'C', "Sharks existed before trees.",
        'D', "A triangle has three sides."
    );

    public List<String> decode(String sequence) {
        if (sequence == null || !sequence.matches("[A-D]{1,16}")) {
            throw new IllegalArgumentException(
                "Glyph sequence must contain 1–16 characters from A to D"
            );
        }

        return sequence.chars()
            .mapToObj(value -> MEANINGS.get((char) value))
            .toList();
    }
}