package com.yasarsafali.rag_backend.service;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.dto.external.ExternalDocumentChange;

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

    // =========================
    // İndirilmiş dosyayı (External API kaydının tüm alanlarıyla) chunk'lara
    // bölüp embed edip Chroma'ya yazar. Metadata, item.toMetadata() ile
    // External API yanıtındaki tüm alanları taşır (ileride bu alanlara göre
    // filtreleme yapılabilsin diye).
    // =========================
    public int ingest(File file, ExternalDocumentChange item) {
        String text;

        try (FileInputStream stream = new FileInputStream(file)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(stream, handler, new Metadata(), new ParseContext());
            text = handler.toString();
        } catch (Exception e) {
            throw new RuntimeException("Belge işleme hatası: " + item.originalName(), e);
        }

        Map<String, Object> metadata = item.toMetadata();
        int chunkIndex = 0;
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String chunk = text.substring(start, end).trim();
            start = end;

            if (chunk.length() < 30) continue;

            List<Float> embedding = embeddingService.embed(chunk);
            chromaClient.add(item.id() + "-c" + chunkIndex, chunk, embedding, metadata);
            chunkIndex++;
        }

        String topicName = item.originalName().replaceFirst("\\.[^.]+$", "");
        topicRegistry.register(topicName);
        return chunkIndex;
    }

    public void deleteDocument(String documentId) {
        chromaClient.delete(documentId);
    }

    // =========================
    // Silinen (event_type=deleted) kayıtlar için indirilecek bir dosya yok;
    // yine de kayıt Chroma'da iz olarak tutulsun diye tek satırlık bir
    // yer tutucu eklenir. Metadata'daki eventType="deleted" sayesinde
    // ChromaClient.query() bu satırı arama sonuçlarından otomatik hariç tutar.
    // =========================
    public void indexDeletedMarker(ExternalDocumentChange item) {
        Map<String, Object> metadata = item.toMetadata();
        String placeholder = "[SİLİNDİ] " + item.originalName();
        List<Float> embedding = embeddingService.embed(placeholder);
        chromaClient.add(item.id() + "-deleted", placeholder, embedding, metadata);
    }
}
