package com.yasarsafali.rag_backend.service.external;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.yasarsafali.rag_backend.dto.external.ExternalDocumentModificationsResponse;
import com.yasarsafali.rag_backend.dto.external.ExternalDocumentsChangesResponse;

import reactor.core.publisher.Flux;

@Service
public class ExternalApiClient {

    @Value("${external-api.base-url}")
    private String baseUrl;

    @Value("${external-api.api-key}")
    private String apiKey;

    @Value("${external-api.request-timeout-seconds:30}")
    private int requestTimeoutSeconds;

    @Value("${external-api.download-timeout-seconds:120}")
    private int downloadTimeoutSeconds;

    private final WebClient webClient;

    public ExternalApiClient(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    public ExternalDocumentsChangesResponse getChanges(String since, int limit) {
        return webClient.get()
                .uri(baseUrl + "/documents/changes?since={since}&limit={limit}", since, limit)
                .header("X-API-Key", apiKey)
                .retrieve()
                .bodyToMono(ExternalDocumentsChangesResponse.class)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .block();
    }

    // =========================
    // Yeni senkronizasyon senaryosu: cursor artik RFC3339 timestamp degil,
    // satir bazli tamsayi (row_cursor). items bos donunce daha fazla kayit
    // yok demektir.
    // =========================
    public ExternalDocumentModificationsResponse getModifications(long cursor, int limit) {
        return webClient.get()
                .uri(baseUrl + "/documents/modifications?cursor={cursor}&limit={limit}", cursor, limit)
                .header("X-API-Key", apiKey)
                .retrieve()
                .bodyToMono(ExternalDocumentModificationsResponse.class)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .block();
    }

    // =========================
    // Dosyayı cdn_url'den indirip geçici dosyaya yazar (büyük dosyalarda
    // bellekte tutmamak için akış olarak yazılır). CDN yavaş/takılırsa
    // sonsuza kadar beklememesi için timeout var; süre aşımında dosya
    // "failed" sayılır, çağıran thread bloke olmadan devam eder.
    // =========================
    public File download(String downloadUrl, String originalName) {
        File temp;
        try {
            temp = File.createTempFile("extdoc-", suffixOf(originalName));
        } catch (IOException e) {
            throw new RuntimeException("Geçici dosya oluşturulamadı: " + originalName, e);
        }

        Flux<DataBuffer> body = webClient.get()
                .uri(downloadUrl)
                .retrieve()
                .bodyToFlux(DataBuffer.class);

        try {
            DataBufferUtils.write(body, temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                    .block();
        } catch (RuntimeException e) {
            temp.delete();
            throw new RuntimeException("Dosya indirilemedi (" + downloadTimeoutSeconds + "s timeout): " + originalName, e);
        }

        return temp;
    }

    private String suffixOf(String name) {
        int dot = name != null ? name.lastIndexOf('.') : -1;
        return dot >= 0 ? name.substring(dot) : ".bin";
    }
}
