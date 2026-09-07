package com.yasarsafali.rag_backend.dto.external;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExternalDocumentModificationsResponse(
        List<ExternalDocumentModification> items,
        @JsonProperty("next_cursor") long nextCursor
) {
}
