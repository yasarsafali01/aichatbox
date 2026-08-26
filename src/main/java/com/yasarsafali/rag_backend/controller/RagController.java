package com.yasarsafali.rag_backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.dto.rag.RagAddRequest;
import com.yasarsafali.rag_backend.dto.rag.RagAskRequest;
import com.yasarsafali.rag_backend.service.ProfanityFilterService;
import com.yasarsafali.rag_backend.service.RagService;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final RagService service;
    private final ProfanityFilterService profanityFilterService;

    public RagController(RagService service, ProfanityFilterService profanityFilterService) {
        this.service = service;
        this.profanityFilterService = profanityFilterService;
    }

    @PostMapping("/add")
    public Object add(@RequestBody RagAddRequest request) {
        profanityFilterService.assertClean(request.text());
        return service.addDocument(request.text());
    }

    @PostMapping("/ask")
    public Object ask(
            @RequestBody RagAskRequest request,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "") String userName) {
        profanityFilterService.assertClean(request.question());
        return service.ask(request.question(), userName.trim(), request.model());
    }

    @GetMapping("/models")
    public List<String> models() {
        return service.listModels();
    }
}
