package com.yasarsafali.rag_backend.service;

import java.io.File;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
public class PdfIngestionService {

    private static final int CHUNK_SIZE = 800;

    private final OllamaEmbeddingService embeddingService;
    private final ChromaClient chromaClient;
    private final TopicRegistry topicRegistry;

    public PdfIngestionService(OllamaEmbeddingService embeddingService,
                               ChromaClient chromaClient,
                               TopicRegistry topicRegistry) {
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
        this.topicRegistry = topicRegistry;
    }

    public int ingest(String pdfPath) {
        int chunkIndex = 0;
        String fileId = UUID.randomUUID().toString().substring(0, 8);
        PDFTextStripper stripper = new PDFTextStripper();

        try (PDDocument doc = Loader.loadPDF(new File(pdfPath))) {
            int totalPages = doc.getNumberOfPages();

            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(doc).trim();

                if (pageText.length() < 30) continue;

                int start = 0;
                while (start < pageText.length()) {
                    int end = Math.min(start + CHUNK_SIZE, pageText.length());
                    String chunk = pageText.substring(start, end).trim();
                    start = end;

                    if (chunk.length() < 30) continue;

                    List<Float> embedding = embeddingService.embed(chunk);
                    chromaClient.add(fileId + "-p" + page + "-c" + chunkIndex, chunk, embedding);
                    chunkIndex++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("PDF işleme hatası: " + pdfPath, e);
        }

        String topicName = new File(pdfPath).getName().replaceFirst("\\.pdf$", "");
        topicRegistry.register(topicName);
        return chunkIndex;
    }
}
