package com.edupulse.ingestion.ingestion.chunking;

import com.edupulse.ingestion.ingestion.reader.ParsedPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchicalChunkerTest {

    private static String sentences(int n, String word) {
        return IntStream.range(0, n).mapToObj(i -> "Sentence " + i + " about " + word + " policy requirements for employees.")
                .collect(Collectors.joining(" "));
    }

    @Test
    void parentsStayWithinPagesAndChildrenPointToParents() {
        List<ParsedPage> pages = List.of(
                new ParsedPage(1, null, sentences(300, "password")),
                new ParsedPage(2, null, sentences(40, "incident")));
        HierarchicalChunker.Result r = new HierarchicalChunker(1000, 200, 22).chunk(pages);

        assertThat(r.parents()).hasSizeGreaterThan(2);
        assertThat(r.parents()).allSatisfy(p -> assertThat(p.pageNumber()).isIn(1, 2));
        assertThat(r.parents().stream().filter(p -> p.pageNumber() == 2).map(ParentChunk::text))
                .allSatisfy(t -> assertThat(t).doesNotContain("password"));
        assertThat(r.children().size()).isGreaterThan(r.parents().size());

        var parentIds = r.parents().stream().map(ParentChunk::id).collect(Collectors.toSet());
        assertThat(r.children()).allSatisfy(c -> assertThat(parentIds).contains(c.parentId()));
        // global, gap-free chunk index (used for neighbour stitching)
        assertThat(r.children().stream().map(ChildChunk::index).toList())
                .isEqualTo(IntStream.range(0, r.children().size()).boxed().toList());
    }

    @Test
    void childrenCarryOverlapFromPreviousSibling() {
        HierarchicalChunker.Result r = new HierarchicalChunker(1000, 200, 10)
                .chunk(List.of(new ParsedPage(1, null, sentences(120, "mfa"))));
        ChildChunk first = r.children().get(0);
        ChildChunk second = r.children().get(1);
        assertThat(second.parentId()).isEqualTo(first.parentId());
        assertThat(second.text()).startsWith(HierarchicalChunker.tailWords(first.ownText(), 10));
        assertThat(second.text()).endsWith(second.ownText());
        assertThat(first.text()).isEqualTo(first.ownText());
    }
}
