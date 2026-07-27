package com.yasarsafali.rag_backend.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.service.OllamaClient;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/benchmark")
@RequiredArgsConstructor
public class BenchmarkController {

    private final OllamaClient ollamaClient;

    public record BenchmarkResult(
            String model,
            String question,
            String answer,
            long responseTimeMs,
            Instant timestamp) {
    }

    @PostMapping("/run")
    public List<BenchmarkResult> run(@RequestBody List<String> questions) {
        return questions.stream()
                .map(this::runOne)
                .toList();
    }

    private BenchmarkResult runOne(String question) {
        long start = System.currentTimeMillis();
        String answer = ollamaClient.generate(question);
        long elapsed = System.currentTimeMillis() - start;
        return new BenchmarkResult(
                ollamaClient.getModel(), question, answer, elapsed, Instant.now());
    }
}
