package com.yasarsafali.rag_backend.controller;


import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.service.OllamaEmbeddingService;

@RestController
public class EmbeddingController {

    private final OllamaEmbeddingService service;

    public EmbeddingController(
            OllamaEmbeddingService service
    ) {
        this.service = service;
    }

    @GetMapping("/embed")
    public List<Float> embed(
            @RequestParam String text
    ) {
        return service.embed(text);
    }
}