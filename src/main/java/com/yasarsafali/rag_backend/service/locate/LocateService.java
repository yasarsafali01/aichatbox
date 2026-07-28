package com.yasarsafali.rag_backend.service.locate;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.dto.locate.LocateResult;
import com.yasarsafali.rag_backend.service.OllamaEmbeddingService;

@Service
public class LocateService {

    private final OllamaEmbeddingService embeddingService;
    private final LocateChromaClient chromaClient;

    @Value("${locate.distance-threshold:340.0}")
    private double distanceThreshold;

    @Value("${locate.max-results:5}")
    private int maxResults;

    public LocateService(OllamaEmbeddingService embeddingService, LocateChromaClient chromaClient) {
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
    }

    public List<LocateResult> locate(String question) {
        List<Float> embedding = embeddingService.embed(question);
        LocateChromaClient.QueryResult result = chromaClient.query(embedding, maxResults);

        List<Double> distances = result.distances();
        List<Map<String, Object>> metadatas = result.metadatas();

        Map<String, LocateResult> bestByLocation = new LinkedHashMap<>();

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

        return bestByLocation.values().stream()
                .sorted(Comparator.comparingDouble(LocateResult::distance))
                .toList();
    }
}
