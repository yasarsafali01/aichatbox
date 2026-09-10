package com.yasarsafali.rag_backend.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.yasarsafali.rag_backend.RagBackendApplication;
import com.yasarsafali.rag_backend.dto.external.ExternalDocumentChange;
import com.yasarsafali.rag_backend.dto.external.ExternalDocumentModification;
import com.yasarsafali.rag_backend.dto.external.ExternalDocumentModificationsResponse;
import com.yasarsafali.rag_backend.service.DocumentIngestionService;
import com.yasarsafali.rag_backend.service.external.ExternalApiClient;
import com.yasarsafali.rag_backend.service.locate.LocateIndexingService;

// =========================
// ReindexTool'un locate-only kopyasi. RAG koleksiyonuna (rag_documents)
// hic dokunmaz - sadece LocateIndexingService uzerinden locate_documents
// koleksiyonunu yeniden indeksler. LocateIndexingService.ingest() artik
// search_document: [baslik] onekiyle zenginlestirilmis embedding uretiyor;
// bu araci calistirmak, koleksiyondaki eski (onneksiz) chunk'lari yeni
// mimariye gore baştan yazar.
//
// Calistirma: mvnw spring-boot:run -Dspring-boot.run.main-class=com.yasarsafali.rag_backend.tools.LocateReindexTool
// Argumanlar (ikisi de opsiyonel): [baslangicCursor] [sayfaBasinaKayit]
// Ornek: ... -Dspring-boot.run.arguments="0,1000"
// =========================
public class LocateReindexTool {

    private static final int PARALLELISM = 10;
    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final Path FAILED_IDS_FILE = Path.of("logs", "locate-reindex-failed-ids.txt");

    private static final AtomicInteger processed = new AtomicInteger(0);
    private static final AtomicInteger upserted = new AtomicInteger(0);
    private static final AtomicInteger deleted = new AtomicInteger(0);
    private static final AtomicInteger skipped = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);

    public static void main(String[] rawArgs) {
        String[] args = splitArgs(rawArgs);

        long startCursor = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        int pageSize = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PAGE_SIZE;

        resetFailedIdsFile();

        ConfigurableApplicationContext context = new SpringApplicationBuilder(RagBackendApplication.class)
                .web(WebApplicationType.NONE)
                .run();

        try {
            ExternalApiClient apiClient = context.getBean(ExternalApiClient.class);
            LocateIndexingService locateIndexingService = context.getBean(LocateIndexingService.class);

            long cursor = startCursor;
            long startedAt = System.currentTimeMillis();

            System.out.printf("Locate reindex başladı. cursor=%d pageSize=%d thread=%d%n", cursor, pageSize, PARALLELISM);

            while (true) {
                ExternalDocumentModificationsResponse page = apiClient.getModifications(cursor, pageSize);
                List<ExternalDocumentModification> items = page.items();

                if (items.isEmpty()) {
                    break;
                }

                processPageInParallel(items, locateIndexingService, apiClient);
                cursor = page.nextCursor();
            }

            long elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000;
            System.out.println("============================================================");
            System.out.println("LOCATE REINDEX BİTTİ");
            System.out.printf(
                    "işlenen=%d başarılı=%d silindi=%d atlandı=%d hata=%d son_cursor=%d süre=%ds%n",
                    processed.get(), upserted.get(), deleted.get(), skipped.get(), failed.get(), cursor, elapsedSeconds);
            if (failed.get() > 0) {
                System.out.println("Hatalı dosya id'leri: " + FAILED_IDS_FILE.toAbsolutePath());
            }
            System.out.println("============================================================");
        } finally {
            context.close();
        }

        System.exit(0);
    }

    private static void processPageInParallel(List<ExternalDocumentModification> items,
                                                LocateIndexingService locateIndexingService,
                                                ExternalApiClient apiClient) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLELISM, items.size()));
        try {
            List<CompletableFuture<Void>> futures = items.stream()
                    .map(item -> CompletableFuture.runAsync(
                            () -> handle(item, locateIndexingService, apiClient), pool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }
    }

    private static void handle(ExternalDocumentModification mod,
                                LocateIndexingService locateIndexingService,
                                ExternalApiClient apiClient) {
        ExternalDocumentChange item = mod.toChange();
        try {
            // Once eski chunk'lari temizle (idempotent upsert / silme) - sadece
            // locate koleksiyonunda, rag_documents'a hic dokunulmuyor.
            locateIndexingService.deleteDocument(item.id());

            if (item.isDeleted()) {
                locateIndexingService.indexDeletedMarker(item);
                deleted.incrementAndGet();
                printProgress(mod, "SİLİNDİ");
                return;
            }

            if (!DocumentIngestionService.isSupported(new File(item.originalName()))) {
                skipped.incrementAndGet();
                printProgress(mod, "ATLANDI");
                return;
            }

            File temp = apiClient.download(item.cdnUrl(), item.originalName());
            try {
                locateIndexingService.ingest(temp, item);
                upserted.incrementAndGet();
                printProgress(mod, "OK");
            } finally {
                temp.delete();
            }
        } catch (Exception e) {
            failed.incrementAndGet();
            appendFailedId(item.id());
            printProgress(mod, "HATA: " + e.getMessage());
        } finally {
            processed.incrementAndGet();
        }
    }

    private static void resetFailedIdsFile() {
        try {
            Files.createDirectories(FAILED_IDS_FILE.getParent());
            Files.writeString(FAILED_IDS_FILE, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("Uyarı: " + FAILED_IDS_FILE + " sıfırlanamadı: " + e.getMessage());
        }
    }

    private static synchronized void appendFailedId(String documentId) {
        try {
            Files.writeString(FAILED_IDS_FILE, documentId + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("Uyarı: " + documentId + " " + FAILED_IDS_FILE + " dosyasına yazılamadı: " + e.getMessage());
        }
    }

    private static synchronized void printProgress(ExternalDocumentModification mod, String status) {
        System.out.printf("[row:%d] işlenen=%d başarılı=%d silindi=%d atlandı=%d hata=%d | %-8s %s%n",
                mod.rowCursor(), processed.get(), upserted.get(), deleted.get(), skipped.get(), failed.get(),
                status, truncate(mod.originalName(), 70));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String[] splitArgs(String[] rawArgs) {
        List<String> result = new ArrayList<>();
        for (String raw : rawArgs) {
            for (String part : raw.split(",")) {
                if (!part.isBlank()) {
                    result.add(part.trim());
                }
            }
        }
        return result.toArray(new String[0]);
    }
}
