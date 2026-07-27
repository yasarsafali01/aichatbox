package com.yasarsafali.rag_backend.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class OllamaClient {

    private final WebClient client =
            WebClient.create("http://localhost:11434");

    @Value("${spring.ai.ollama.rag.model:qwen2.5}")
    private String model;

    @Value("${spring.ai.ollama.rag.keep-alive:30m}")
    private String keepAlive;

    @SuppressWarnings("unchecked")
    public String generate(String prompt) {
        Map<String, Object> res = client.post()
                .uri("/api/generate")
                .bodyValue(Map.of(
                        "model", model,
                        "prompt", prompt,
                        "stream", false,
                        "keep_alive", keepAlive
                ))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        if (res == null) return "";
        Object response = res.get("response");
        return response != null ? response.toString() : "";
    }

    public String getModel() {
        return model;
    }
}