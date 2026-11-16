package com.edupulse.ingestion.ingestion.reader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cleans extracted text before chunking:
 * <ul>
 *   <li>removes running headers/footers repeated on most pages (e.g. "EAAlgorithm Internal Security Handbook", "v1.0"),</li>
 *   <li>removes bare page-number lines,</li>
 *   <li>re-joins words hyphenated across line breaks ("EAAl-\ngorithm" → "EAAlgorithm"),</li>
 *   <li>normalises bullets and collapses whitespace.</li>
 * </ul>
 */
public final class TextNormalizer {

    private static final Pattern HYPHEN_BREAK = Pattern.compile("(\\p{L})-\\s*\\n\\s*(\\p{Ll})");
    private static final Pattern PAGE_NUMBER_LINE = Pattern.compile("^\\s*(page\\s*)?\\d{1,4}\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLETS = Pattern.compile("[\\u2022\\u25CF\\u25AA\\u25E6\\uF0B7\\uFFFD]");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");

    private TextNormalizer() {
    }

    /** Lines that appear on more than half of the pages (min. 3 pages) are treated as header/footer. */
    public static Set<String> detectRepeatedLines(List<String> pages) {
        if (pages.size() < 3) {
            return Set.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String page : pages) {
            Set<String> seen = new HashSet<>();
            for (String line : page.split("\\R")) {
                String key = line.strip();
                if (!key.isEmpty() && key.length() <= 120 && seen.add(key)) {
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }
        Set<String> repeated = new HashSet<>();
        counts.forEach((line, count) -> {
            if (count > pages.size() / 2) {
                repeated.add(line);
            }
        });
        return repeated;
    }

    public static String normalize(String text, Set<String> repeatedLines) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        t = HYPHEN_BREAK.matcher(t).replaceAll("$1$2");
        StringBuilder sb = new StringBuilder(t.length());
        for (String line : t.split("\n", -1)) {
            String stripped = line.strip();
            if (repeatedLines.contains(stripped) || PAGE_NUMBER_LINE.matcher(stripped).matches()) {
                continue;
            }
            stripped = BULLETS.matcher(stripped).replaceAll("•");
            stripped = SPACES.matcher(stripped).replaceAll(" ");
            sb.append(stripped).append('\n');
        }
        return MANY_NEWLINES.matcher(sb.toString()).replaceAll("\n\n").strip();
    }
}
