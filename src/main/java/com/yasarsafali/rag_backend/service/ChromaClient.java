package com.yasarsafali.rag_backend.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
@Service
public class ChromaClient {

    @Value("${chroma.base-url}")
    private String baseUrl;

    @Value("${chroma.tenant-id}")
    private String tenantId;

    @Value("${chroma.database}")
    private String database;

    @Value("${chroma.collection}")
    private String collection;

    private final WebClient webClient;

    public ChromaClient(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    // =========================
    // ADD DOCUMENT (embedding insert)
    // =========================
    public Object add(String id, String document, List<Float> embedding) {

        String url = baseUrl +
                "/api/v2/tenants/" + tenantId +
                "/databases/" + database +
                "/collections/" + collection +
                "/add";

        Map<String, Object> body = Map.of(
                "ids", List.of(id),
                "documents", List.of(document),
                "embeddings", List.of(embedding)
        );

        return webClient.post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    public record QueryResult(List<String> documents, List<Double> distances) {}

    // =========================
    // QUERY (RAG SEARCH)
    // =========================
    @SuppressWarnings("unchecked")
    public QueryResult query(List<Float> embedding) {
        String url = baseUrl + "/api/v2/tenants/" + tenantId +
                "/databases/" + database +
                "/collections/" + collection + "/query";

        Map<String, Object> body = new HashMap<>();
        body.put("query_embeddings", List.of(embedding));
        body.put("n_results", 5);
        body.put("include", List.of("documents", "distances"));

        Map<String, Object> response = webClient.post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        if (response == null) return new QueryResult(List.of(), List.of());

        List<List<String>> docs = (List<List<String>>) response.get("documents");
        List<List<Double>> dists = (List<List<Double>>) response.get("distances");

        List<String> docList = (docs != null && !docs.isEmpty()) ? docs.get(0) : List.of();
        List<Double> distList = (dists != null && !dists.isEmpty()) ? dists.get(0) : List.of();

        return new QueryResult(docList, distList);
    }
}