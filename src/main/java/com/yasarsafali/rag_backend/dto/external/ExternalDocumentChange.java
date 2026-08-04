package com.yasarsafali.rag_backend.dto.external;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExternalDocumentChange(
        String id,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("original_name") String originalName,
        @JsonProperty("file_type") String fileType,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("file_size") long fileSize,
        @JsonProperty("cdn_url") String cdnUrl,
        @JsonProperty("birim_id") int birimId,
        @JsonProperty("birim_name") Map<String, String> birimName,
        @JsonProperty("updated_at") String updatedAt
) {
    public boolean isDeleted() {
        return "deleted".equals(eventType);
    }

    // =========================
    // External API yanıtındaki TÜM alanları Chroma metadata'sına taşır;
    // ileride belirtilen alanlara (birimId, fileType, updatedAt vb.) göre
    // filtreleme yapılabilsin diye. Chroma metadata düz (flat) olduğundan
    // birim_name {tr,en} map'i ayrı iki alana açılır.
    // =========================
    public Map<String, Object> toMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", id);
        metadata.put("eventType", nullToEmpty(eventType));
        metadata.put("fileName", nullToEmpty(originalName));
        metadata.put("fileType", nullToEmpty(fileType));
        metadata.put("mimeType", nullToEmpty(mimeType));
        metadata.put("fileSize", fileSize);
        metadata.put("cdnUrl", nullToEmpty(cdnUrl));
        metadata.put("birimId", birimId);
        metadata.put("birimNameTr", birimName != null ? birimName.getOrDefault("tr", "") : "");
        metadata.put("birimNameEn", birimName != null ? birimName.getOrDefault("en", "") : "");
        metadata.put("updatedAt", nullToEmpty(updatedAt));
        return metadata;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
