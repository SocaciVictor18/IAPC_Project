package com.edupulse.compliance.gap;

/** Test bridge to package-private parsing helpers. */
public final class GapParsingAccess {

    private GapParsingAccess() {
    }

    public static GapModels.Judgement judgement(String s) {
        return GapAnalysisService.parseJudgement(s);
    }

    public static GapModels.Extraction extraction(String s) {
        return GapAnalysisService.parseExtraction(s);
    }

    public static String status(String s) {
        return GapAnalysisService.normalizeStatus(s);
    }
}
