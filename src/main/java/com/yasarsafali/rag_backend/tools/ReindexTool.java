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
// Bagimsiz calisan tam yeniden indeksleme araci. Web sunucusu ayaklanmadan
// (WebApplicationType.NONE) ayni Spring context'i kurar, mevcut ingestion
// servislerini (chunking/embedding/Chroma) oldugu gibi kullanir; sadece
// External API'den veriyi /documents/modifications uzerinden, RFC3339
// yerine tamsayi row_cursor ile sayfalar.
//
// Calistirma: mvnw spring-boot:run -Dspring-boot.run.main-class=com.yasarsafali.rag_backend.tools.ReindexTool
// Argumanlar (ikisi de opsiyonel): [baslangicCursor] [sayfaBasinaKayit]
// Ornek: ... -Dspring-boot.run.arguments="0,1000"
// =========================
public class ReindexTool {

    private static final int PARALLELISM = 10;
    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final Path FAILED_IDS_FILE = Path.of("logs", "reindex-failed-ids.txt");

    private static final AtomicInteger processed = new AtomicInteger(0);
    private static final AtomicInteger upserted = new AtomicInteger(0);
    private static final AtomicInteger deleted = new AtomicInteger(0);
    private static final AtomicInteger skipped = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);

    public static void main(String[] rawArgs) {
        // -Dspring-boot.run.arguments bosluk ile ayirir, ama "0,10" gibi virgullu
        // tek parca girilirse de calissin diye burada ayrica virgulle de bolunuyor.
        String[] args = splitArgs(rawArgs);

        long startCursor = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        int pageSize = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PAGE_SIZE;

        resetFailedIdsFile();

        ConfigurableApplicationContext context = new SpringApplicationBuilder(RagBackendApplication.class)
                .web(WebApplicationType.NONE)
                .run();

        try {
            ExternalApiClient apiClient = context.getBean(ExternalApiClient.class);
            DocumentIngestionService ragIngestionService = context.getBean(DocumentIngestionService.class);
            LocateIndexingService locateIndexingService = context.getBean(LocateIndexingService.class);

            long cursor = startCursor;
            long startedAt = System.currentTimeMillis();

            System.out.printf("Reindex başladı. cursor=%d pageSize=%d thread=%d%n", cursor, pageSize, PARALLELISM);

            while (true) {
                ExternalDocumentModificationsResponse page = apiClient.getModifications(cursor, pageSize);
                List<ExternalDocumentModification> items = page.items();

                if (items.isEmpty()) {
                    break;
                }

                processPageInParallel(items, ragIngestionService, locateIndexingService, apiClient);
                cursor = page.nextCursor();
            }

            long elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000;
            System.out.println("============================================================");
            System.out.println("İŞLEM BİTTİ");
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

        // Reactor Netty/WebClient baglanti havuzundan artakalan thread'ler
        // JVM'in kapanmasini engelleyip surecin "asilmis" gibi gorunmesine
        // sebep olabiliyor; islem bittigini kesin belli etmek icin acikca cik.
        System.exit(0);
    }

    private static void processPageInParallel(List<ExternalDocumentModification> items,
                                                DocumentIngestionService ragIngestionService,
                                                LocateIndexingService locateIndexingService,
                                                ExternalApiClient apiClient) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLELISM, items.size()));
        try {
            List<CompletableFuture<Void>> futures = items.stream()
                    .map(item -> CompletableFuture.runAsync(
                            () -> handle(item, ragIngestionService, locateIndexingService, apiClient), pool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }
    }

    private static void handle(ExternalDocumentModification mod,
                                DocumentIngestionService ragIngestionService,
                                LocateIndexingService locateIndexingService,
                                ExternalApiClient apiClient) {
        ExternalDocumentChange item = mod.toChange();
        try {
            // Her durumda once eski chunk'lari temizle (idempotent upsert / silme)
            ragIngestionService.deleteDocument(item.id());
            locateIndexingService.deleteDocument(item.id());

            if (item.isDeleted()) {
                ragIngestionService.indexDeletedMarker(item);
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
                ragIngestionService.ingest(temp, item);
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

    // Hatali dosyanin id'sini aninda diske ekler (calisma ortada kesilse bile
    // o ana kadarki hatalar kaybolmasin diye), boylece en sonda bu dosyadaki
    // id'ler /sync/import gibi bir akista tekrar denenebilir.
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

    // -Dspring-boot.run.arguments="0 10" bosluga gore ayirir ve dogru sekilde
    // iki elemanli bir diziyle gelir; ama biri yanlislikla "0,10" gibi virgullu
    // tek parca girerse de calissin diye burada ek olarak virgulden de bolunuyor.
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
