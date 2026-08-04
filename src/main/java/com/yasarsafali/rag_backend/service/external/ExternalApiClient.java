package com.yasarsafali.rag_backend.service.external;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardOpenOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.yasarsafali.rag_backend.dto.external.ExternalDocumentsChangesResponse;

import reactor.core.publisher.Flux;

@Service
public class ExternalApiClient {

    @Value("${external-api.base-url}")
    private String baseUrl;

    @Value("${external-api.api-key}")
    private String apiKey;

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
                .block();
    }

    // =========================
    // Dosyayı cdn_url'den indirip geçici dosyaya yazar (büyük dosyalarda
    // bellekte tutmamak için akış olarak yazılır)
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

        DataBufferUtils.write(body, temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                .block();

        return temp;
    }

    private String suffixOf(String name) {
        int dot = name != null ? name.lastIndexOf('.') : -1;
        return dot >= 0 ? name.substring(dot) : ".bin";
    }
}
