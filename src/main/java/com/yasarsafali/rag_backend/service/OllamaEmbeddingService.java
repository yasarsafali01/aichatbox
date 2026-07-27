package com.yasarsafali.rag_backend.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class OllamaEmbeddingService {

    private final String baseUrl;
    private final String model;
    private final WebClient webClient;

    public OllamaEmbeddingService(
            WebClient.Builder builder,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}") String model
    ) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.webClient = builder.build();
    }

    // =========================
    // TEXT → EMBEDDING
    // =========================
    public List<Float> embed(String text) {

        String url = baseUrl + "/api/embeddings";

        Map<String, Object> body = Map.of(
                "model", model,
                "prompt", text
        );

        Map<String, Object> response = webClient.post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        @SuppressWarnings("unchecked")
        List<Number> raw = (List<Number>) response.get("embedding");
        return raw.stream().map(Number::floatValue).collect(Collectors.toList());
    }
}