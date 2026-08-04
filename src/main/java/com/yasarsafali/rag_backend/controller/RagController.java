package com.yasarsafali.rag_backend.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.dto.rag.RagAddRequest;
import com.yasarsafali.rag_backend.dto.rag.RagAskRequest;
import com.yasarsafali.rag_backend.service.RagService;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final RagService service;

    public RagController(RagService service) {
        this.service = service;
    }

    @PostMapping("/add")
    public Object add(@RequestBody RagAddRequest request) {
        return service.addDocument(request.text());
    }

    @PostMapping("/ask")
    public Object ask(
            @RequestBody RagAskRequest request,
            @RequestHeader(value = "X-User-Name", required = false, defaultValue = "") String userName) {
        return service.ask(request.question(), userName.trim());
    }
}
