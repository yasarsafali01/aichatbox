package com.yasarsafali.rag_backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.service.external.ExternalDocumentSyncService;

@RestController
@RequestMapping("/sync")
public class SyncController {

    private final ExternalDocumentSyncService syncService;

    public SyncController(ExternalDocumentSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/run")
    public String run() {
        boolean started = syncService.start();
        return started ? "Senkronizasyon başlatıldı." : "Zaten devam eden bir senkronizasyon var.";
    }

    @GetMapping("/status")
    public Object status() {
        return syncService.status();
    }
}
