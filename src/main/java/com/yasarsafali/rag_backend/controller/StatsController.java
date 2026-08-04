package com.yasarsafali.rag_backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.dto.stats.ChromaStatsResponse;
import com.yasarsafali.rag_backend.service.ChromaStatsService;

@RestController
@RequestMapping("/stats")
public class StatsController {

    private final ChromaStatsService chromaStatsService;

    public StatsController(ChromaStatsService chromaStatsService) {
        this.chromaStatsService = chromaStatsService;
    }

    @GetMapping("/chroma")
    public ChromaStatsResponse chroma() {
        return chromaStatsService.getStats();
    }
}
