package com.yasarsafali.rag_backend.service.locate;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.dto.locate.LocateResult;
import com.yasarsafali.rag_backend.service.OllamaEmbeddingService;
import com.yasarsafali.rag_backend.service.QueryExpansionService;

@Service
public class LocateService {

    // Chroma'dan her sorgu varyanti icin cekilecek ham aday sayisi (esik/gruplama
    // sonrasi maxResults'a dusuruluyor). Tek sorguda top-5'in disina dusen dogru
    // chunk'i, sorgu genislemesiyle birlikte yakalama sansini artirmak icin
    // maxResults'tan daha genis tutuluyor.
    private static final int CANDIDATE_POOL_SIZE = 15;

    private final OllamaEmbeddingService embeddingService;
    private final LocateChromaClient chromaClient;
    private final QueryExpansionService queryExpansionService;

    @Value("${locate.distance-threshold:340.0}")
    private double distanceThreshold;

    @Value("${locate.max-results:5}")
    private int maxResults;

    public LocateService(OllamaEmbeddingService embeddingService,
                          LocateChromaClient chromaClient,
                          QueryExpansionService queryExpansionService) {
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
        this.queryExpansionService = queryExpansionService;
    }

    public List<LocateResult> locate(String question) {
        Map<String, LocateResult> bestByLocation = new LinkedHashMap<>();

        for (String q : queryExpansionService.expand(question)) {
            List<Float> embedding = embeddingService.embed(q);
            LocateChromaClient.QueryResult result = chromaClient.query(embedding, CANDIDATE_POOL_SIZE);

            List<Double> distances = result.distances();
            List<Map<String, Object>> metadatas = result.metadatas();

            for (int i = 0; i < metadatas.size(); i++) {
                double distance = (distances.size() > i && distances.get(i) != null) ? distances.get(i) : Double.MAX_VALUE;
                if (distance > distanceThreshold) continue;

                Map<String, Object> meta = metadatas.get(i);
                String fileName = String.valueOf(meta.get("fileName"));
                String location = String.valueOf(meta.get("location"));
                String key = fileName + "#" + location;

                LocateResult existing = bestByLocation.get(key);
                if (existing == null || distance < existing.distance()) {
                    bestByLocation.put(key, new LocateResult(
                            String.valueOf(meta.get("title")),
                            fileName,
                            location,
                            String.valueOf(meta.get("url")),
                            distance
                    ));
                }
            }
        }

        return bestByLocation.values().stream()
                .sorted(Comparator.comparingDouble(LocateResult::distance))
                .limit(maxResults)
                .toList();
    }
}
