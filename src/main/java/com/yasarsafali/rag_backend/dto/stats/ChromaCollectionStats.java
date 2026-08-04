package com.yasarsafali.rag_backend.dto.stats;

import java.util.List;
import java.util.Map;

public record ChromaCollectionStats(
        String id,
        String name,
        long chunkCount,
        long fileCount,
        List<String> metadataFields,
        Map<String, Object> sampleMetadata
) {
}
