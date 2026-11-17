package com.edupulse.ingestion.domain;

public enum DocumentStatus {
    UPLOADED, QUEUED, PARSING, CHUNKING, EMBEDDING, INDEXED, FAILED, DELETED;

    public boolean inProgress() {
        return this == QUEUED || this == PARSING || this == CHUNKING || this == EMBEDDING;
    }
}
