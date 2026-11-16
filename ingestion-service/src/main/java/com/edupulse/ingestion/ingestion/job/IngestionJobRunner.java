package com.edupulse.ingestion.ingestion.job;

import com.edupulse.ingestion.ingestion.pipeline.IngestionPipeline;
import com.edupulse.ingestion.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.jobrunr.jobs.annotations.Job;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JobRunr entry point for large uploads (&gt; 10 MB). Jobs are persisted in Postgres, so any
 * ingestion-service replica picks them up (horizontal scale-out) and they survive restarts.
 */
@Component
@RequiredArgsConstructor
public class IngestionJobRunner {

    private final IngestionPipeline pipeline;
    private final DocumentRepository documents;

    @Job(name = "Ingest document %0", retries = 2)
    public void run(UUID documentId) {
        documents.jobStarted(documentId);
        pipeline.process(documentId);
    }
}
