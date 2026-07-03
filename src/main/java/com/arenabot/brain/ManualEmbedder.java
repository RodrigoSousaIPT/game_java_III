package com.arenabot.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits the 3750-byte arena manual into 15 sections used as the RAG corpus
 * for vault unlocking. Pure-Java so the unit tests don't need Ollama running.
 *
 * <p>Section headers in the manual follow the shape {@code SECÇÃO NN - TITLE}
 * (all caps Portuguese). We split on that.
 */
public final class ManualEmbedder {

    private static final Pattern HEADER = Pattern.compile("SEC[ÇC][ÃA]O\\s*\\d+\\s*-\\s*[^\\n]+");

    public static List<ManualSection> split(String manual) {
        List<ManualSection> out = new ArrayList<>();
        if (manual == null || manual.isBlank()) return out;
        Matcher m = HEADER.matcher(manual);
        List<int[]> ranges = new ArrayList<>();
        while (m.find()) ranges.add(new int[]{ m.start(), m.end() });
        if (ranges.isEmpty()) {
            out.add(new ManualSection(0, "FULL", manual));
            return out;
        }
        for (int i = 0; i < ranges.size(); i++) {
            int start = ranges.get(i)[0];
            int end = i + 1 < ranges.size() ? ranges.get(i + 1)[0] : manual.length();
            String header = manual.substring(start, ranges.get(i)[1]).trim();
            String body = manual.substring(ranges.get(i)[1], end).trim();
            int num = extractNumber(header);
            out.add(new ManualSection(num, header, body));
        }
        return out;
    }

    private static int extractNumber(String header) {
        Matcher n = Pattern.compile("\\d+").matcher(header);
        return n.find() ? Integer.parseInt(n.group()) : 0;
    }

    public record ManualSection(int number, String header, String body) {
        public String textForEmbedding() { return header + "\n\n" + body; }
    }
}
