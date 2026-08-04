package com.yasarsafali.rag_backend.dto.stats;

import java.util.List;

public record ChromaStatsResponse(
        int collectionCount,
        long totalChunkCount,
        List<ChromaCollectionStats> collections
) {
}
