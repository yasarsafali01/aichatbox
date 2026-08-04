package com.yasarsafali.rag_backend.service.external;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.yasarsafali.rag_backend.dto.external.ExternalDocumentChange;
import com.yasarsafali.rag_backend.dto.external.ExternalDocumentsChangesResponse;
import com.yasarsafali.rag_backend.service.DocumentIngestionService;
import com.yasarsafali.rag_backend.service.locate.LocateIndexingService;

@Service
public class ExternalDocumentSyncService {

    private final ExternalApiClient apiClient;
    private final DocumentIngestionService ragIngestionService;
    private final LocateIndexingService locateIndexingService;
    private final ObjectMapper objectMapper;

    @Value("${external-api.initial-since}")
    private String initialSince;

    @Value("${external-api.page-size:100}")
    private int defaultLimit;

    private final AtomicReference<String> cursor = new AtomicReference<>();

    private final AtomicInteger totalItems = new AtomicInteger(0);
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger upserted = new AtomicInteger(0);
    private final AtomicInteger deleted = new AtomicInteger(0);
    private final AtomicInteger skipped = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private volatile boolean running = false;
    private volatile String lastError = "";

    public ExternalDocumentSyncService(ExternalApiClient apiClient,
                                        DocumentIngestionService ragIngestionService,
                                        LocateIndexingService locateIndexingService,
                                        ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.ragIngestionService = ragIngestionService;
        this.locateIndexingService = locateIndexingService;
        this.objectMapper = objectMapper;
    }

    private void resetCounters() {
        totalItems.set(0);
        processed.set(0);
        upserted.set(0);
        deleted.set(0);
        skipped.set(0);
        failed.set(0);
        lastError = "";
    }

    public synchronized boolean start(String sinceOverride, Integer limitOverride) {
        if (running) return false;

        resetCounters();
        running = true;

        String effectiveSince = (sinceOverride != null && !sinceOverride.isBlank())
                ? sinceOverride
                : (cursor.get() != null ? cursor.get() : initialSince);
        int effectiveLimit = (limitOverride != null && limitOverride > 0) ? limitOverride : defaultLimit;

        Thread worker = new Thread(() -> runSync(effectiveSince, effectiveLimit), "external-doc-sync");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    // =========================
    // Tek seferlik, elle hazırlanmış bir JSON dosyasından içe aktarma.
    // External API'yi hiç çağırmaz, cursor'ı değiştirmez; External API'nin
    // /documents/changes items[] formatındaki bir JSON dizisini diskten okur
    // ve `parts` kadar parçaya bölüp paralel işler. Dosya okunamazsa/bozuksa
    // senkron olarak hata fırlatır (arka plan işi hiç başlamaz).
    // =========================
    public synchronized boolean startImport(String filePath, Integer partsOverride) {
        if (running) return false;

        List<ExternalDocumentChange> items = readItemsFromFile(filePath);

        resetCounters();
        running = true;

        int requestedParts = (partsOverride != null && partsOverride > 0) ? partsOverride : 1;
        List<List<ExternalDocumentChange>> chunks = splitInto(items, requestedParts);

        Thread worker = new Thread(() -> runImport(chunks), "external-doc-import");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private List<ExternalDocumentChange> readItemsFromFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath belirtilmedi");
        }

        File file = new File(filePath);
        if (!file.isFile()) {
            throw new IllegalArgumentException("Dosya bulunamadı: " + filePath);
        }

        try {
            return objectMapper.readValue(file, new TypeReference<List<ExternalDocumentChange>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON okunamadı: " + e.getMessage(), e);
        }
    }

    private List<List<ExternalDocumentChange>> splitInto(List<ExternalDocumentChange> items, int parts) {
        int total = items.size();
        int effectiveParts = Math.max(1, Math.min(parts, Math.max(total, 1)));

        List<List<ExternalDocumentChange>> chunks = new ArrayList<>();
        int base = total / effectiveParts;
        int remainder = total % effectiveParts;
        int index = 0;

        for (int i = 0; i < effectiveParts; i++) {
            int size = base + (i < remainder ? 1 : 0);
            if (size == 0) continue;
            chunks.add(new ArrayList<>(items.subList(index, index + size)));
            index += size;
        }

        return chunks;
    }

    private void runImport(List<List<ExternalDocumentChange>> chunks) {
        try {
            int total = chunks.stream().mapToInt(List::size).sum();
            totalItems.addAndGet(total);

            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, chunks.size()));
            try {
                List<CompletableFuture<Void>> futures = chunks.stream()
                        .map(chunk -> CompletableFuture.runAsync(() -> {
                            for (ExternalDocumentChange item : chunk) {
                                try {
                                    handle(item);
                                } catch (Exception e) {
                                    failed.incrementAndGet();
                                    lastError = item.originalName() + ": " + e.getMessage();
                                } finally {
                                    processed.incrementAndGet();
                                }
                            }
                        }, pool))
                        .toList();

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                pool.shutdown();
            }
        } catch (Exception e) {
            lastError = "İçe aktarma hatası: " + e.getMessage();
        } finally {
            running = false;
        }
    }

    private static final int PARALLELISM = 8;

    // External API tek çağrıda en fazla `limit` (maks. 100) dosya döner.
    // items boş dönene kadar sayfalar (cursor ile) sırayla çekilir; her
    // sayfadaki dosyalar ise kendi içinde paralel işlenir.
    private void runSync(String since, int limit) {
        try {
            while (true) {
                ExternalDocumentsChangesResponse page = apiClient.getChanges(since, limit);
                List<ExternalDocumentChange> items = page.items();

                if (items.isEmpty()) {
                    since = page.nextCursor();
                    break;
                }

                totalItems.addAndGet(items.size());
                processPageInParallel(items);

                since = page.nextCursor();
            }

            cursor.set(since);
        } catch (Exception e) {
            lastError = "Senkronizasyon hatası: " + e.getMessage();
        } finally {
            running = false;
        }
    }

    private void processPageInParallel(List<ExternalDocumentChange> items) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLELISM, items.size()));
        try {
            List<CompletableFuture<Void>> futures = items.stream()
                    .map(item -> CompletableFuture.runAsync(() -> {
                        try {
                            handle(item);
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            lastError = item.originalName() + ": " + e.getMessage();
                        } finally {
                            processed.incrementAndGet();
                        }
                    }, pool))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }
    }

    private void handle(ExternalDocumentChange item) {
        // Her durumda önce eski chunk'ları temizle (idempotent upsert / silme)
        ragIngestionService.deleteDocument(item.id());
        locateIndexingService.deleteDocument(item.id());

        if (item.isDeleted()) {
            // Belge silindi; dosya indirilemez ama kayıt Chroma'da iz olarak
            // kalsın diye yer tutucu eklenir (arama sonuçlarına yansımaz,
            // bkz. ChromaClient/LocateChromaClient.query() where filtresi).
            ragIngestionService.indexDeletedMarker(item);
            locateIndexingService.indexDeletedMarker(item);
            deleted.incrementAndGet();
            return;
        }

        if (!DocumentIngestionService.isSupported(new File(item.originalName()))) {
            skipped.incrementAndGet();
            return;
        }

        File temp = apiClient.download(item.cdnUrl(), item.originalName());
        try {
            ragIngestionService.ingest(temp, item);
            locateIndexingService.ingest(temp, item);
            upserted.incrementAndGet();
        } finally {
            temp.delete();
        }
    }

    public Map<String, Object> status() {
        return Map.ofEntries(
                Map.entry("running", running),
                Map.entry("totalItems", totalItems.get()),
                Map.entry("processed", processed.get()),
                Map.entry("upserted", upserted.get()),
                Map.entry("deleted", deleted.get()),
                Map.entry("skipped", skipped.get()),
                Map.entry("failed", failed.get()),
                Map.entry("lastError", lastError),
                Map.entry("cursor", cursor.get() == null ? initialSince : cursor.get())
        );
    }
}
