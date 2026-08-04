package com.yasarsafali.rag_backend.service;

import java.time.Duration;
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

    public OllamaClient(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        this.client = WebClient.create(baseUrl);
    }

    @SuppressWarnings("unchecked")
    public String generate(String prompt) {
        Map<String, Object> res = client.post()
                .uri("/api/generate")
                .bodyValue(Map.of(
                        "model", model,
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

    private static String stripThinking(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    public String getModel() {
        return model;
    }
}