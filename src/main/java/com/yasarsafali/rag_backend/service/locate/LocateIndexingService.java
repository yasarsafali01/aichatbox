package com.yasarsafali.rag_backend.service.locate;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.dto.external.ExternalDocumentChange;
import com.yasarsafali.rag_backend.service.OllamaEmbeddingService;

@Service
public class LocateIndexingService {

    private final List<LocatableExtractor> extractors;
    private final PageChunker chunker;
    private final OllamaEmbeddingService embeddingService;
    private final LocateChromaClient chromaClient;

    public LocateIndexingService(List<LocatableExtractor> extractors,
                                  PageChunker chunker,
                                  OllamaEmbeddingService embeddingService,
                                  LocateChromaClient chromaClient) {
        this.extractors = extractors;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
    }

    // =========================
    // İndirilmiş dosyayı (External API kaydının tüm alanlarıyla) sayfa/
    // paragraf/slayt bazlı birimlere ayırıp, chunk'layıp Chroma'ya yazar.
    // Metadata, item.toMetadata() ile External API yanıtındaki tüm alanları
    // + title/location/url'i taşır (ileride bu alanlara göre filtreleme
    // yapılabilsin diye).
    // =========================
    public int ingest(File file, ExternalDocumentChange item) {
        LocatableExtractor extractor = extractors.stream()
                .filter(e -> e.supports(file))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Desteklenmeyen dosya türü: " + item.originalName()));

        String title = item.originalName().replaceFirst("\\.[^.]+$", "");
        Map<String, Object> baseMetadata = item.toMetadata();
        baseMetadata.put("title", title);
        baseMetadata.put("url", item.cdnUrl() != null ? item.cdnUrl() : "");

        List<LocatableUnit> pageUnits = extractor.extract(file);
        int chunkCount = 0;

        for (LocatableUnit unit : pageUnits) {
            List<String> chunks = chunker.chunk(unit.text());
            for (String chunk : chunks) {
                List<Float> embedding = embeddingService.embed(chunk);
                Map<String, Object> metadata = new HashMap<>(baseMetadata);
                metadata.put("location", unit.location());
                chromaClient.add(item.id() + "-c" + chunkCount, chunk, embedding, metadata);
                chunkCount++;
            }
        }

        return chunkCount;
    }

    public void deleteDocument(String documentId) {
        chromaClient.delete(documentId);
    }
}
