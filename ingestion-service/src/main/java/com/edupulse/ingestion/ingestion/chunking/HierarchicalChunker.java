package com.edupulse.ingestion.ingestion.chunking;

import com.edupulse.ingestion.ingestion.reader.ParsedPage;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Parent-child chunking (FR "Hierarchical Document Chunking").
 * <ol>
 *   <li>Each page is split into parents of ~{@code parentTokens} tokens; a parent never crosses a page,
 *       so it has exactly one page for citations.</li>
 *   <li>Each parent is split into children of ~{@code childTokens} tokens. {@link TokenTextSplitter} has no
 *       overlap option (course observation), so child <i>i</i> is prefixed with the last
 *       {@code overlapWords} words of child <i>i-1</i>.</li>
 * </ol>
 * Children are embedded and matched; parents are what the LLM finally reads.
 */
public class HierarchicalChunker {

    private final TokenTextSplitter parentSplitter;
    private final TokenTextSplitter childSplitter;
    private final int overlapWords;

    public HierarchicalChunker(int parentTokens, int childTokens, int overlapWords) {
        this(parentTokens, 350, childTokens, 100, overlapWords);
    }

    public HierarchicalChunker(int parentTokens, int parentMinChars, int childTokens, int childMinChars, int overlapWords) {
        this.parentSplitter = TokenTextSplitter.builder()
                .withChunkSize(parentTokens)
                .withMinChunkSizeChars(parentMinChars)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(100_000)
                .withKeepSeparator(true)
                .build();
        this.childSplitter = TokenTextSplitter.builder()
                .withChunkSize(childTokens)
                .withMinChunkSizeChars(childMinChars)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(100_000)
                .withKeepSeparator(true)
                .build();
        this.overlapWords = overlapWords;
    }

    public record Result(List<ParentChunk> parents, List<ChildChunk> children) {
    }

    public Result chunk(List<ParsedPage> pages) {
        List<ParentChunk> parents = new ArrayList<>();
        List<ChildChunk> children = new ArrayList<>();
        int parentIndex = 0;
        int childIndex = 0;
        for (ParsedPage page : pages) {
            for (String parentText : split(parentSplitter, page.text())) {
                ParentChunk parent = new ParentChunk(UUID.randomUUID(), parentIndex++, page.pageNumber(),
                        page.sectionRef(), parentText, approxTokens(parentText));
                parents.add(parent);
                String previous = null;
                for (String own : split(childSplitter, parentText)) {
                    String embedded = previous == null ? own : tailWords(previous, overlapWords) + " " + own;
                    children.add(new ChildChunk(UUID.randomUUID(), parent.id(), parent.index(), childIndex++,
                            page.pageNumber(), page.sectionRef(), embedded, own));
                    previous = own;
                }
            }
        }
        return new Result(parents, children);
    }

    private static List<String> split(TokenTextSplitter splitter, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return splitter.split(new Document(text)).stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .map(String::strip)
                .toList();
    }

    static String tailWords(String text, int n) {
        if (n <= 0) {
            return "";
        }
        String[] words = text.strip().split("\\s+");
        return String.join(" ", Arrays.copyOfRange(words, Math.max(0, words.length - n), words.length));
    }

    /** Cheap estimate (≈ 0.75 words per token) good enough for statistics. */
    static int approxTokens(String text) {
        return (int) Math.ceil(text.split("\\s+").length / 0.75);
    }
}
