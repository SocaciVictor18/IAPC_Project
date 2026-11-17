package com.edupulse.ingestion.service;

import com.edupulse.common.web.ApiException;
import com.edupulse.ingestion.config.IngestionProperties;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Validates uploads by their <b>real</b> content type (Tika magic-byte detection), not by the
 * client-provided Content-Type or extension alone. Only PDF, DOCX and TXT are accepted.
 */
@Component
@RequiredArgsConstructor
public class UploadValidator {

    public static final String PDF = "application/pdf";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String TXT = "text/plain";

    private static final Map<String, String> EXTENSIONS = Map.of("pdf", PDF, "docx", DOCX, "txt", TXT);

    private final IngestionProperties props;
    private final Tika tika = new Tika();

    public String validate(String filename, long size, InputStream content) throws IOException {
        if (size <= 0) {
            throw ApiException.badRequest("Empty file");
        }
        if (size > props.maxUploadBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "File exceeds " + props.maxUploadBytes() + " bytes");
        }
        String ext = extension(filename);
        String expected = EXTENSIONS.get(ext);
        if (expected == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only .pdf, .docx and .txt files are accepted");
        }
        String detected = tika.detect(content, filename);
        boolean ok = switch (expected) {
            case TXT -> detected.startsWith("text/");
            default -> detected.equals(expected);
        };
        if (!ok) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File content (" + detected + ") does not match the ." + ext + " extension");
        }
        return expected;
    }

    /** Keeps only a safe display name (no directories, no control characters). */
    public static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "document";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").trim();
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
