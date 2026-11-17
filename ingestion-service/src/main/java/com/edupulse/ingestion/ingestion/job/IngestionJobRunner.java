package com.edupulse.ingestion.ingestion.job;

import com.edupulse.ingestion.ingestion.pipeline.IngestionPipeline;
import lombok.RequiredArgsConstructor;
import org.jobrunr.jobs.annotations.Job;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JobRunr background job for uploads larger than 10 MB. Jobs are stored in Postgres, so they
 * survive restarts and any ingestion-service instance can pick them up.
 */
@Component
@RequiredArgsConstructor
public class IngestionJobRunner {

    private final IngestionPipeline pipeline;

    @Job(name = "Ingest document %0", retries = 2)
    public void run(UUID documentId) {
        pipeline.process(documentId);
    }
}
