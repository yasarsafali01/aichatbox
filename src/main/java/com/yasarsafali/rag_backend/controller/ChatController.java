package com.yasarsafali.rag_backend.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yasarsafali.rag_backend.service.ProfanityFilterService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatClient chatClient;
    private final ProfanityFilterService profanityFilterService;

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        profanityFilterService.assertClean(q);

        return chatClient.prompt()
                .user(q)
                .call()
                .content();
    }
}