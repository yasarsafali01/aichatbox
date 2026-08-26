package com.yasarsafali.rag_backend.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.exception.InvalidModelException;

@Service
public class RagService {

    private final OllamaEmbeddingService embeddingService;
    private final ChromaClient chromaClient;
    private final OllamaClient ollamaClient;
    private final TopicRegistry topicRegistry;
    private final QueryExpansionService queryExpansionService;

    @Value("${chroma.distance-threshold:340.0}")
    private double distanceThreshold;

    public RagService(OllamaEmbeddingService embeddingService,
                      ChromaClient chromaClient,
                      OllamaClient ollamaClient,
                      TopicRegistry topicRegistry,
                      QueryExpansionService queryExpansionService) {
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
        this.ollamaClient = ollamaClient;
        this.topicRegistry = topicRegistry;
        this.queryExpansionService = queryExpansionService;
    }

    public Object addDocument(String text) {
        List<Float> embedding = embeddingService.embed(text);
        return chromaClient.add("doc-" + System.currentTimeMillis(), text, embedding);
    }

    public List<String> listModels() {
        return ollamaClient.listModels();
    }

    public String ask(String question, String userName, String model) {
        if (model != null && !model.isBlank() && !ollamaClient.isKnownModel(model)) {
            throw new InvalidModelException(model, ollamaClient.listModels());
        }

        List<String> queries = queryExpansionService.expand(question);

        Set<String> seen = new LinkedHashSet<>();
        for (String q : queries) {
            List<Float> embedding = embeddingService.embed(q);
            ChromaClient.QueryResult result = chromaClient.query(embedding);
            List<String> docs = result.documents();
            List<Double> distances = result.distances();
            for (int i = 0; i < docs.size(); i++) {
                double dist = Double.MAX_VALUE;
                if (distances.size() > i && distances.get(i) != null) {
                    dist = distances.get(i);
                }
                if (dist <= distanceThreshold) {
                    seen.add(docs.get(i));
                }
            }
        }

        if (seen.isEmpty()) {
            return buildNoInfoResponse(userName);
        }

        String context = String.join("\n\n", seen);
        String prompt = """
                Yalnızca aşağıdaki belgelerden yararlanarak Türkçe yanıt ver.

                KURALLAR:
                - Belgeler sorunun cevabını içeriyorsa, yanıtı KESİNLİKLE aşağıdaki formatta ver. Başka hiçbir giriş, açıklama veya yorum cümlesi ekleme.
                - Belgeler sorunun cevabını içermiyorsa SADECE şunu yaz: "Bu konuda bilgim yok."

                Format (belgeler cevabı içeriyorsa):
                Bu konu hakkında bildiklerim:
                <cevabı buraya yaz>

                Örnek:
                Belgeler:
                Kayıt yenileme işlemleri her yıl eylül ayının ilk iki haftasında yapılır.

                Soru: Kayıt yenileme ne zaman yapılır?
                Yanıt:
                Bu konu hakkında bildiklerim:
                Kayıt yenileme işlemleri her yıl eylül ayının ilk iki haftasında yapılır.

                Belgeler:
                %s

                Soru: %s
                Yanıt:""".formatted(context, question);

        return ollamaClient.generate(prompt, model);
    }

    private String address(String userName) {
        return (userName == null || userName.isBlank()) ? "" : " " + userName;
    }

    private String buildNoInfoResponse(String userName) {
        Set<String> topics = topicRegistry.getTopics();
        StringBuilder sb = new StringBuilder();
        sb.append("Üzgünüm").append(address(userName)).append(", bu konuda bilgim yok.");
        if (!topics.isEmpty()) {
            sb.append(" Yalnızca şu konularda yardımcı olabilirim: ");
            sb.append(String.join(", ", topics)).append(".");
        }
        return sb.toString();
    }
}
