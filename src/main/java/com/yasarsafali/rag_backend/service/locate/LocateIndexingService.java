package com.yasarsafali.rag_backend.service.locate;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.service.OllamaEmbeddingService;

@Service
public class LocateIndexingService {

    private final List<LocatableExtractor> extractors;
    private final PageChunker chunker;
    private final OllamaEmbeddingService embeddingService;
    private final LocateChromaClient chromaClient;

    @Value("${locate.pdf.base-url}")
    private String pdfBaseUrl;

    public LocateIndexingService(List<LocatableExtractor> extractors,
                                  PageChunker chunker,
                                  OllamaEmbeddingService embeddingService,
                                  LocateChromaClient chromaClient) {
        this.extractors = extractors;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
    }

    public boolean isSupported(File file) {
        return extractors.stream().anyMatch(e -> e.supports(file));
    }

    public int ingest(String filePath) {
        File file = new File(filePath);
        LocatableExtractor extractor = extractors.stream()
                .filter(e -> e.supports(file))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Desteklenmeyen dosya türü: " + filePath));

        String fileName = file.getName();
        String title = fileName.replaceFirst("\\.[^.]+$", "");
        String url = pdfBaseUrl + encode(fileName);
        String fileId = UUID.randomUUID().toString().substring(0, 8);

        List<LocatableUnit> pageUnits = extractor.extract(file);
        int chunkCount = 0;

        for (LocatableUnit unit : pageUnits) {
            List<String> chunks = chunker.chunk(unit.text());
            for (String chunk : chunks) {
                List<Float> embedding = embeddingService.embed(chunk);
                Map<String, Object> metadata = Map.of(
                        "title", title,
                        "fileName", fileName,
                        "location", unit.location(),
                        "url", url
                );
                chromaClient.add(fileId + "-c" + chunkCount, chunk, embedding, metadata);
                chunkCount++;
            }
        }

        return chunkCount;
    }

    private static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
