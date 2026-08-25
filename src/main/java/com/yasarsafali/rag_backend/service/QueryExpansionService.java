package com.yasarsafali.rag_backend.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class QueryExpansionService {

    private final OllamaClient ollamaClient;

    public QueryExpansionService(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    /** Soruyu, aynı anlama gelen 2 farklı ifadeyle birlikte döner (orijinaliyle toplam en fazla 3 varyant). */
    public List<String> expand(String question) {
        String expansionPrompt = """
                Aşağıdaki soruyu, aynı anlama gelen 2 farklı şekilde yeniden ifade et.
                Sadece soruları yaz, her biri ayrı satırda, başka hiçbir şey yazma.

                Soru: %s""".formatted(question);

        String raw = ollamaClient.generate(expansionPrompt);

        List<String> variants = new ArrayList<>();
        variants.add(question);
        Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank() && !s.equalsIgnoreCase(question))
                .limit(2)
                .forEach(variants::add);

        return variants;
    }
}
