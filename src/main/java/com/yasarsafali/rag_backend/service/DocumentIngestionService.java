package com.yasarsafali.rag_backend.service;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

@Service
public class DocumentIngestionService {

    private static final int CHUNK_SIZE = 800;

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx");

    private final OllamaEmbeddingService embeddingService;
    private final ChromaClient chromaClient;
    private final TopicRegistry topicRegistry;

    public DocumentIngestionService(OllamaEmbeddingService embeddingService,
                                     ChromaClient chromaClient,
                                     TopicRegistry topicRegistry) {
        this.embeddingService = embeddingService;
        this.chromaClient = chromaClient;
        this.topicRegistry = topicRegistry;
    }

    public static boolean isSupported(File file) {
        return SUPPORTED_EXTENSIONS.contains(extension(file.getName()));
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    public int ingest(String filePath) {
        File file = new File(filePath);
        String text;

        try (FileInputStream stream = new FileInputStream(file)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(stream, handler, new Metadata(), new ParseContext());
            text = handler.toString();
        } catch (Exception e) {
            throw new RuntimeException("Belge işleme hatası: " + filePath, e);
        }

        String fileId = UUID.randomUUID().toString().substring(0, 8);
        int chunkIndex = 0;
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String chunk = text.substring(start, end).trim();
            start = end;

            if (chunk.length() < 30) continue;

            List<Float> embedding = embeddingService.embed(chunk);
            chromaClient.add(fileId + "-c" + chunkIndex, chunk, embedding);
            chunkIndex++;
        }

        String topicName = file.getName().replaceFirst("\\.[^.]+$", "");
        topicRegistry.register(topicName);
        return chunkIndex;
    }
}
