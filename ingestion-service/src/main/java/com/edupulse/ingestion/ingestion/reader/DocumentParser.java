package com.edupulse.ingestion.ingestion.reader;

import com.edupulse.ingestion.config.IngestionProperties;
import com.edupulse.ingestion.service.UploadValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Turns a file into citable {@link ParsedPage}s, keeping page information for citations.
 * <ul>
 *   <li>PDF – {@link PagePdfDocumentReader} (one Document per page, course config: left alignment, no line trimming)</li>
 *   <li>DOCX – {@link TikaDocumentReader}; no fixed pages, so paragraphs are grouped and cited as "¶ a-b"</li>
 *   <li>TXT – logical pages of N lines, cited as page + "lines a-b"</li>
 * </ul>
 * Pages are normalised in parallel on virtual threads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParser {

    public static final String MIME_PDF = UploadValidator.PDF;
    public static final String MIME_DOCX = UploadValidator.DOCX;
    public static final String MIME_TXT = UploadValidator.TXT;

    private final IngestionProperties props;
    private final ExecutorService virtualThreadExecutor;

    public List<ParsedPage> parse(Path file, String mimeType) {
        List<ParsedPage> raw = switch (mimeType) {
            case MIME_PDF -> readPdf(file);
            case MIME_DOCX -> readDocx(file);
            case MIME_TXT -> readTxt(file);
            default -> throw new IllegalArgumentException("Unsupported type " + mimeType);
        };
        return normalizeInParallel(raw);
    }

    private List<ParsedPage> readPdf(Path file) {
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        .withLeftAlignment(true)
                        .withNumberOfTopTextLinesToDelete(0)
                        .withNumberOfBottomTextLinesToDelete(0)
                        .build())
                .withPagesPerDocument(1)
                .build();
        List<Document> docs = new PagePdfDocumentReader(new FileSystemResource(file), config).get();
        List<ParsedPage> pages = new ArrayList<>();
        int fallback = 1;
        for (Document d : docs) {
            Object p = d.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
            int page = p instanceof Number n ? n.intValue() : fallback;
            pages.add(new ParsedPage(page, null, d.getText() == null ? "" : d.getText()));
            fallback = page + 1;
        }
        return pages;
    }

    private List<ParsedPage> readDocx(Path file) {
        List<Document> docs = new TikaDocumentReader(new FileSystemResource(file)).get();
        String text = docs.stream().map(Document::getText).filter(t -> t != null).reduce("", (a, b) -> a + "\n" + b);
        List<String> paragraphs = new ArrayList<>();
        for (String para : text.split("\\n\\s*\\n|\\n")) {
            if (!para.isBlank()) {
                paragraphs.add(para.strip());
            }
        }
        int per = Math.max(1, props.chunking().docxParagraphsPerSection());
        List<ParsedPage> sections = new ArrayList<>();
        String heading = null;
        for (int i = 0; i < paragraphs.size(); i += per) {
            int end = Math.min(paragraphs.size(), i + per);
            List<String> block = paragraphs.subList(i, end);
            for (String p : block) {
                if (looksLikeHeading(p)) {
                    heading = p;
                    break;
                }
            }
            String ref = (heading != null ? heading + " " : "") + "(¶ " + (i + 1) + "-" + end + ")";
            sections.add(new ParsedPage(null, ref, String.join("\n\n", block)));
        }
        return sections;
    }

    private List<ParsedPage> readTxt(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read text file", e);
        }
        int per = Math.max(1, props.chunking().txtLinesPerPage());
        List<ParsedPage> pages = new ArrayList<>();
        for (int i = 0, page = 1; i < lines.size(); i += per, page++) {
            int end = Math.min(lines.size(), i + per);
            pages.add(new ParsedPage(page, "lines " + (i + 1) + "-" + end, String.join("\n", lines.subList(i, end))));
        }
        return pages;
    }

    private List<ParsedPage> normalizeInParallel(List<ParsedPage> raw) {
        Set<String> repeated = TextNormalizer.detectRepeatedLines(raw.stream().map(ParsedPage::text).toList());
        if (!repeated.isEmpty()) {
            log.debug("Removing {} repeated header/footer lines", repeated.size());
        }
        List<Future<ParsedPage>> futures = new ArrayList<>();
        for (ParsedPage p : raw) {
            futures.add(virtualThreadExecutor.submit(() ->
                    new ParsedPage(p.pageNumber(), p.sectionRef(), TextNormalizer.normalize(p.text(), repeated))));
        }
        List<ParsedPage> out = new ArrayList<>();
        for (Future<ParsedPage> f : futures) {
            try {
                ParsedPage p = f.get();
                if (!p.text().isBlank()) {
                    out.add(p);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (Exception e) {
                throw new IllegalStateException("Page normalisation failed", e);
            }
        }
        return out;
    }

    private static boolean looksLikeHeading(String p) {
        return p.length() <= 80 && !p.endsWith(".") && Character.isUpperCase(p.charAt(0)) && p.split("\\s+").length <= 10;
    }
}
