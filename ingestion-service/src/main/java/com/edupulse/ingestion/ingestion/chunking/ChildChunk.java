package com.edupulse.ingestion.ingestion.chunking;

import java.util.UUID;

/**
 * A ~200-token chunk embedded for precise matching.
 *
 * @param text      text that is embedded (overlap prefix + own text)
 * @param ownText   the child's own text, without the overlap (used for the citation snippet)
 * @param index     global index in the document (for neighbour stitching)
 */
public record ChildChunk(UUID id, UUID parentId, int parentIndex, int index, Integer pageNumber, String sectionRef,
                         String text, String ownText) {
}
