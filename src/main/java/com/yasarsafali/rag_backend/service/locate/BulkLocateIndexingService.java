package com.yasarsafali.rag_backend.service.locate;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

@Service
public class BulkLocateIndexingService {

    private final LocateIndexingService indexingService;

    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger succeeded = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private volatile boolean running = false;
    private volatile String lastError = "";

    public BulkLocateIndexingService(LocateIndexingService indexingService) {
        this.indexingService = indexingService;
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
                .filter(indexingService::isSupported)
                .toList();

        total.set(supported.size());
        processed.set(0);
        succeeded.set(0);
        failed.set(0);
        running = true;

        Thread worker = new Thread(() -> runIndexing(supported), "bulk-locate-indexing");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void runIndexing(List<File> files) {
        for (File file : files) {
            try {
                indexingService.ingest(file.getAbsolutePath());
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
