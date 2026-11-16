package com.edupulse.compliance.rag.postprocessing;

/** Test bridge to package-private snippet selection. */
public final class SnippetAccess {

    private SnippetAccess() {
    }

    public static String best(String text, String question, int max) {
        return CitationHeaderPostProcessor.bestSnippet(text, question, max);
    }
}
