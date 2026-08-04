package com.yasarsafali.rag_backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.yasarsafali.rag_backend.dto.stats.ChromaCollectionStats;
import com.yasarsafali.rag_backend.dto.stats.ChromaStatsResponse;

@Service
public class ChromaStatsService {

    private static final int PAGE_SIZE = 5000;

    @Value("${chroma.base-url}")
    private String baseUrl;

    @Value("${chroma.tenant-id}")
    private String tenantId;

    @Value("${chroma.database}")
    private String database;

    private final WebClient webClient;

    public ChromaStatsService(WebClient.Builder builder) {
        // metadata sayfaları varsayılan 256KB tampon sınırını aşabildiği için büyütülüyor
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
        this.webClient = builder.exchangeStrategies(strategies).build();
    }

    public ChromaStatsResponse getStats() {
        List<Map<String, Object>> rawCollections = listCollections();

        List<ChromaCollectionStats> collectionStats = new ArrayList<>();
        long totalChunks = 0;

        for (Map<String, Object> col : rawCollections) {
            String id = String.valueOf(col.get("id"));
            String name = String.valueOf(col.get("name"));

            long chunkCount = count(id);
            totalChunks += chunkCount;

            collectionStats.add(inspectCollection(id, name, chunkCount));
        }

        return new ChromaStatsResponse(rawCollections.size(), totalChunks, collectionStats);
    }

    private String collectionsUrl() {
        return baseUrl + "/api/v2/tenants/" + tenantId + "/databases/" + database + "/collections";
    }

    private List<Map<String, Object>> listCollections() {
        List<Map<String, Object>> result = webClient.get()
                .uri(collectionsUrl())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
        return result != null ? result : List.of();
    }

    private long count(String collectionId) {
        Long result = webClient.get()
                .uri(collectionsUrl() + "/" + collectionId + "/count")
                .retrieve()
                .bodyToMono(Long.class)
                .block();
        return result != null ? result : 0L;
    }

    // Koleksiyonun tüm metadata'sını sayfalayarak tarar: benzersiz dosya
    // sayısını (documentId), gözlemlenen tüm metadata alan adlarını
    // (indexleme formatı) ve örnek bir kayıt (sampleMetadata) çıkarır.
    @SuppressWarnings("unchecked")
    private ChromaCollectionStats inspectCollection(String id, String name, long chunkCount) {
        TreeSet<String> fileIds = new TreeSet<>();
        TreeSet<String> metadataFields = new TreeSet<>();
        Map<String, Object> sample = null;
        int offset = 0;

        while (true) {
            Map<String, Object> body = Map.of(
                    "limit", PAGE_SIZE,
                    "offset", offset,
                    "include", List.of("metadatas")
            );

            Map<String, Object> response = webClient.post()
                    .uri(collectionsUrl() + "/" + id + "/get")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response == null) break;

            List<String> ids = (List<String>) response.get("ids");
            List<Map<String, Object>> metadatas = (List<Map<String, Object>>) response.get("metadatas");

            if (ids == null || ids.isEmpty()) break;

            for (int i = 0; i < ids.size(); i++) {
                Map<String, Object> metadata = (metadatas != null && i < metadatas.size()) ? metadatas.get(i) : null;
                if (metadata == null) continue;

                metadataFields.addAll(metadata.keySet());
                if (sample == null) sample = new LinkedHashMap<>(metadata);

                Object documentId = metadata.get("documentId");
                fileIds.add(documentId != null ? String.valueOf(documentId) : "id-" + ids.get(i));
            }

            if (ids.size() < PAGE_SIZE) break;
            offset += PAGE_SIZE;
        }

        return new ChromaCollectionStats(id, name, chunkCount, fileIds.size(), List.copyOf(metadataFields), sample);
    }
}
