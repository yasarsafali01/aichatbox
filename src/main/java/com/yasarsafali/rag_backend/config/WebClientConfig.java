package com.yasarsafali.rag_backend.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfig {

    // Varsayılan Reactor Netty bağlantı havuzu, 15 paralel worker thread'in
    // Chroma/Ollama/CDN'e eşzamanlı istek atmasına yetmiyordu; thread'ler
    // bağlantı bekleyerek tıkanıyordu. Havuzu büyütüyoruz.
    @Bean
    public WebClient.Builder webClientBuilder() {
        ConnectionProvider provider = ConnectionProvider.builder("aichatbox-pool")
                .maxConnections(200)
                .pendingAcquireMaxCount(2000)
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .build();

        HttpClient httpClient = HttpClient.create(provider);

        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
