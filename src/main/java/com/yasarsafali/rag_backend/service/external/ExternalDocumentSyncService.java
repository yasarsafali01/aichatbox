package com.yasarsafali.rag_backend.service.external;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.dto.external.ExternalDocumentChange;
import com.yasarsafali.rag_backend.dto.external.ExternalDocumentsChangesResponse;
import com.yasarsafali.rag_backend.service.DocumentIngestionService;
import com.yasarsafali.rag_backend.service.locate.LocateIndexingService;

@Service
public class ExternalDocumentSyncService {

    private final ExternalApiClient apiClient;
    private final DocumentIngestionService ragIngestionService;
    private final LocateIndexingService locateIndexingService;

    @Value("${external-api.initial-since}")
    private String initialSince;

    @Value("${external-api.page-size:100}")
    private int pageSize;

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
                                        LocateIndexingService locateIndexingService) {
        this.apiClient = apiClient;
        this.ragIngestionService = ragIngestionService;
        this.locateIndexingService = locateIndexingService;
    }

    public synchronized boolean start() {
        if (running) return false;

        totalItems.set(0);
        processed.set(0);
        upserted.set(0);
        deleted.set(0);
        skipped.set(0);
        failed.set(0);
        lastError = "";
        running = true;

        Thread worker = new Thread(this::runSync, "external-doc-sync");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void runSync() {
        String since = cursor.get() != null ? cursor.get() : initialSince;

        try {
            while (true) {
                ExternalDocumentsChangesResponse page = apiClient.getChanges(since, pageSize);
                List<ExternalDocumentChange> items = page.items();

                if (items.isEmpty()) {
                    since = page.nextCursor();
                    break;
                }

                totalItems.addAndGet(items.size());
                for (ExternalDocumentChange item : items) {
                    try {
                        handle(item);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        lastError = item.originalName() + ": " + e.getMessage();
                    } finally {
                        processed.incrementAndGet();
                    }
                }

                since = page.nextCursor();
            }

            cursor.set(since);
        } catch (Exception e) {
            lastError = "Senkronizasyon hatası: " + e.getMessage();
        } finally {
            running = false;
        }
    }

    private void handle(ExternalDocumentChange item) {
        // Her durumda önce eski chunk'ları temizle (idempotent upsert / silme)
        ragIngestionService.deleteDocument(item.id());
        locateIndexingService.deleteDocument(item.id());

        if (item.isDeleted()) {
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
