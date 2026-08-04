package com.yasarsafali.rag_backend.dto.external;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExternalDocumentsChangesResponse(
        List<ExternalDocumentChange> items,
        @JsonProperty("next_cursor") String nextCursor
) {
}
