package com.yasarsafali.rag_backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.dto.external.SyncImportRequest;
import com.yasarsafali.rag_backend.dto.external.SyncRunRequest;
import com.yasarsafali.rag_backend.service.external.ExternalDocumentSyncService;

@RestController
@RequestMapping("/sync")
public class SyncController {

    private final ExternalDocumentSyncService syncService;

    public SyncController(ExternalDocumentSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/run")
    public String run(@RequestBody(required = false) SyncRunRequest request) {
        String since = request != null ? request.since() : null;
        Integer limit = request != null ? request.limit() : null;
        boolean started = syncService.start(since, limit);
        return started ? "Senkronizasyon başlatıldı." : "Zaten devam eden bir senkronizasyon var.";
    }

    @PostMapping("/import")
    public String importFromFile(@RequestBody SyncImportRequest request) {
        if (request == null || request.filePath() == null || request.filePath().isBlank()) {
            return "Hata: filePath zorunludur.";
        }

        try {
            boolean started = syncService.startImport(request.filePath(), request.parts());
            return started ? "İçe aktarma başlatıldı." : "Zaten devam eden bir senkronizasyon/içe aktarma var.";
        } catch (IllegalArgumentException e) {
            return "Hata: " + e.getMessage();
        }
    }

    @GetMapping("/status")
    public Object status() {
        return syncService.status();
    }
}
