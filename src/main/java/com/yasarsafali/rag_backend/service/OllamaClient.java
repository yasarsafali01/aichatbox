package com.yasarsafali.rag_backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class OllamaClient {

    private final WebClient client;

    @Value("${spring.ai.ollama.rag.model:qwen2.5}")
    private String model;

    @Value("${spring.ai.ollama.rag.keep-alive:30m}")
    private String keepAlive;

    @Value("${spring.ai.ollama.rag.think:false}")
    private boolean think;

    @Value("${spring.ai.ollama.rag.timeout-seconds:180}")
    private int timeoutSeconds;

    @Value("${spring.ai.ollama.rag.models-cache-ttl-seconds:60}")
    private int modelsCacheTtlSeconds;

    private volatile CachedModels cachedModels;

    private record CachedModels(List<String> names, Instant fetchedAt) {
    }

    public OllamaClient(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        this.client = WebClient.create(baseUrl);
    }

    public String generate(String prompt) {
        return generate(prompt, null);
    }

    @SuppressWarnings("unchecked")
    public String generate(String prompt, String requestedModel) {
        String modelToUse = (requestedModel == null || requestedModel.isBlank()) ? model : requestedModel;

        Map<String, Object> res = client.post()
                .uri("/api/generate")
                .bodyValue(Map.of(
                        "model", modelToUse,
                        "prompt", prompt,
                        "stream", false,
                        // Qwen3/DeepSeek-R1 gibi "thinking" modellerinde uzun akıl
                        // yürütme adımını açar/kapatır (spring.ai.ollama.rag.think);
                        // ayrıca aşağıda stripThinking ile <think> bloğu ekstra
                        // güvenlik olarak temizlenir (think:false'u desteklemeyen
                        // modeller için).
                        "think", think,
                        "keep_alive", keepAlive,
                        "options", Map.of(
                                "temperature", 0,
                                "seed", 42
                        )
                ))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        if (res == null) return "";
        Object response = res.get("response");
        return stripThinking(response != null ? response.toString() : "");
    }

    /**
     * Ollama'da o an kurulu, cevap üretebilen (embedding-only olmayan) modelleri döner.
     * Sonuç modelsCacheTtlSeconds boyunca bellekte cache'lenir; kalıcı bir yere yazılmaz.
     */
    @SuppressWarnings("unchecked")
    public List<String> listModels() {
        CachedModels cached = cachedModels;
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).getSeconds() < modelsCacheTtlSeconds) {
            return cached.names();
        }

        Map<String, Object> res = client.get()
                .uri("/api/tags")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(10))
                .block();

        List<String> names = new ArrayList<>();
        if (res != null && res.get("models") instanceof List<?> models) {
            for (Object entry : models) {
                if (entry instanceof Map<?, ?> map && map.get("name") != null && supportsCompletion(map)) {
                    names.add(map.get("name").toString());
                }
            }
        }

        cachedModels = new CachedModels(names, Instant.now());
        return names;
    }

    private static boolean supportsCompletion(Map<?, ?> modelEntry) {
        Object capabilities = modelEntry.get("capabilities");
        if (!(capabilities instanceof List<?> caps)) return true;
        return caps.contains("completion");
    }

    public boolean isKnownModel(String candidate) {
        return listModels().contains(candidate);
    }

    private static String stripThinking(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    public String getModel() {
        return model;
    }
}
