package com.yasarsafali.rag_backend.service;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

@Service
public class BulkIngestionService {

    private final DocumentIngestionService documentIngestionService;

    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger succeeded = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private volatile boolean running = false;
    private volatile String lastError = "";

    public BulkIngestionService(DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    public synchronized boolean start(String folderPath) {
        if (running) return false;

        File folder = new File(folderPath);
        File[] files = folder.listFiles();
        if (files == null) {
            throw new IllegalArgumentException("Klasör bulunamadı: " + folderPath);
        }

        List<File> supported = Arrays.stream(files)
                .filter(File::isFile)
                .filter(DocumentIngestionService::isSupported)
                .toList();

        total.set(supported.size());
        processed.set(0);
        succeeded.set(0);
        failed.set(0);
        running = true;

        Thread worker = new Thread(() -> runIngestion(supported), "bulk-ingestion");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void runIngestion(List<File> files) {
        for (File file : files) {
            try {
                documentIngestionService.ingest(file.getAbsolutePath());
                succeeded.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
                lastError = file.getName() + ": " + e.getMessage();
            } finally {
                processed.incrementAndGet();
            }
        }
        running = false;
    }

    public Map<String, Object> status() {
        return Map.of(
                "running", running,
                "total", total.get(),
                "processed", processed.get(),
                "succeeded", succeeded.get(),
                "failed", failed.get(),
                "remaining", total.get() - processed.get(),
                "lastError", lastError
        );
    }
}
