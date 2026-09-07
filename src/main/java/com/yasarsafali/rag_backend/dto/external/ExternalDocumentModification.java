package com.yasarsafali.rag_backend.dto.external;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

// /documents/modifications ucunun items[] elemani. /documents/changes ile ayni
// temel alanlari tasir, farkli olarak cursor artik updated_at (RFC3339) degil
// row_cursor (tamsayi) uzerinden ilerler; lang ve resource_type de eklendi.
public record ExternalDocumentModification(
        String id,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("original_name") String originalName,
        @JsonProperty("file_type") String fileType,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("file_size") long fileSize,
        @JsonProperty("cdn_url") String cdnUrl,
        @JsonProperty("birim_id") int birimId,
        @JsonProperty("birim_name") Map<String, String> birimName,
        @JsonProperty("updated_at") String updatedAt,
        String lang,
        @JsonProperty("resource_type") String resourceType,
        @JsonProperty("row_cursor") long rowCursor
) {
    public boolean isDeleted() {
        return "deleted".equals(eventType);
    }

    // Mevcut ingestion pipeline'i (DocumentIngestionService/LocateIndexingService)
    // ExternalDocumentChange bekliyor; row_cursor/lang/resource_type Chroma
    // metadata semasinin disinda oldugu icin donusumde tasinmiyor.
    public ExternalDocumentChange toChange() {
        return new ExternalDocumentChange(
                id, eventType, originalName, fileType, mimeType, fileSize,
                cdnUrl, birimId, birimName, updatedAt
        );
    }
}
