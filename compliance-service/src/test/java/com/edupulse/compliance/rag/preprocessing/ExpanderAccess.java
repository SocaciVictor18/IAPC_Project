package com.edupulse.compliance.rag.preprocessing;

import java.util.List;

/** Test bridge to package-private expansion parsing. */
public final class ExpanderAccess {

    private ExpanderAccess() {
    }

    public static List<String> parse(String output, String original, int max) {
        return TracingQueryExpander.parseVariants(output, original, max);
    }
}
