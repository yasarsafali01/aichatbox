package com.yasarsafali.rag_backend.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.dto.quote.AnswerRequest;
import com.yasarsafali.rag_backend.dto.quote.PrefillRequest;
import com.yasarsafali.rag_backend.dto.quote.QuoteStepResponse;
import com.yasarsafali.rag_backend.dto.quote.StartRequest;
import com.yasarsafali.rag_backend.service.quote.QuoteService;

@RestController
@RequestMapping("/quote")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @PostMapping("/start")
    public QuoteStepResponse start(@RequestBody StartRequest request) {
        return quoteService.start(request.brans());
    }

    @PostMapping("/answer")
    public QuoteStepResponse answer(@RequestBody AnswerRequest request) {
        return quoteService.answer(request.sessionId(), request.answer());
    }

    @PostMapping("/{sessionId}/prefill")
    public QuoteStepResponse prefill(@PathVariable String sessionId, @RequestBody PrefillRequest request) {
        return quoteService.prefill(sessionId, request.fields());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Geçersiz istek: " + e.getMessage()));
    }
}
