package com.edupulse.ingestion.ingestion.reader;

/**
 * One citable unit of a document.
 *
 * @param pageNumber real PDF page, logical page for TXT, {@code null} for DOCX (no fixed pages)
 * @param sectionRef human-readable position used when there is no page ("¶ 12-20", "lines 61-120")
 */
public record ParsedPage(Integer pageNumber, String sectionRef, String text) {
}
