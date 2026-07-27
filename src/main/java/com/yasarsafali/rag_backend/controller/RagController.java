package com.yasarsafali.rag_backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.service.BulkIngestionService;
import com.yasarsafali.rag_backend.service.DocumentIngestionService;
import com.yasarsafali.rag_backend.service.RagService;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final RagService service;
    private final DocumentIngestionService documentIngestionService;
    private final BulkIngestionService bulkIngestionService;

    public RagController(RagService service,
                          DocumentIngestionService documentIngestionService,
                          BulkIngestionService bulkIngestionService) {
        this.service = service;
        this.documentIngestionService = documentIngestionService;
        this.bulkIngestionService = bulkIngestionService;
    }

    @PostMapping("/add")
    public Object add(@RequestBody String text) {
        return service.addDocument(text);
    }

    @PostMapping("/ask")
    public Object ask(
            @RequestBody String question,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "") String userName) {
        return service.ask(question, userName.trim());
    }

    @PostMapping("/load-pdf")
    public String loadPdf(@RequestParam String path) {
        int chunks = documentIngestionService.ingest(path);
        return chunks + " chunk ChromaDB'ye yüklendi.";
    }

    @PostMapping("/load-folder")
    public String loadFolder(@RequestParam String path) {
        boolean started = bulkIngestionService.start(path);
        return started ? "Toplu yükleme başlatıldı." : "Zaten devam eden bir yükleme var.";
    }

    @GetMapping("/load-status")
    public Object loadStatus() {
        return bulkIngestionService.status();
    }
}
