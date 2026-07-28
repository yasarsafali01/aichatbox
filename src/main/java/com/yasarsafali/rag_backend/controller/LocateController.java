package com.yasarsafali.rag_backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.dto.locate.LocateResult;
import com.yasarsafali.rag_backend.service.locate.BulkLocateIndexingService;
import com.yasarsafali.rag_backend.service.locate.LocateIndexingService;
import com.yasarsafali.rag_backend.service.locate.LocateService;

@RestController
@RequestMapping("/locate")
public class LocateController {

    private final LocateService locateService;
    private final LocateIndexingService locateIndexingService;
    private final BulkLocateIndexingService bulkLocateIndexingService;

    public LocateController(LocateService locateService,
                             LocateIndexingService locateIndexingService,
                             BulkLocateIndexingService bulkLocateIndexingService) {
        this.locateService = locateService;
        this.locateIndexingService = locateIndexingService;
        this.bulkLocateIndexingService = bulkLocateIndexingService;
    }

    @GetMapping
    public List<LocateResult> locate(@RequestParam String q) {
        return locateService.locate(q);
    }

    @PostMapping("/index-pdf")
    public String indexPdf(@RequestParam String path) {
        int chunks = locateIndexingService.ingest(path);
        return chunks + " chunk indexlendi.";
    }

    @PostMapping("/index-folder")
    public String indexFolder(@RequestParam String path) {
        boolean started = bulkLocateIndexingService.start(path);
        return started ? "Toplu indexleme başlatıldı." : "Zaten devam eden bir indexleme var.";
    }

    @GetMapping("/index-status")
    public Object indexStatus() {
        return bulkLocateIndexingService.status();
    }
}
