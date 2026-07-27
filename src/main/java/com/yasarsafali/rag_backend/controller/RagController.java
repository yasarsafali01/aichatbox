package com.yasarsafali.rag_backend.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.service.PdfIngestionService;
import com.yasarsafali.rag_backend.service.RagService;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final RagService service;
    private final PdfIngestionService pdfIngestionService;

    public RagController(RagService service, PdfIngestionService pdfIngestionService) {
        this.service = service;
        this.pdfIngestionService = pdfIngestionService;
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
        int chunks = pdfIngestionService.ingest(path);
        return chunks + " chunk ChromaDB'ye yüklendi.";
    }
}
