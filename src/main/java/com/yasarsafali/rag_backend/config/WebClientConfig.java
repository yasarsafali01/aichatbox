package com.yasarsafali.rag_backend.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.resolver.DefaultAddressResolverGroup;
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

        // Netty'nin kendi async DNS resolver'i, bazi sunucularin resolv.conf
        // search-domain ayarlarinda (orn. "search .") "Empty label is not a
        // legal name" hatasiyla cozumlemeyi tamamen basarisiz kilabiliyor
        // (bilinen Netty davranisi) - bu ortamda curl/nslookup sorunsuz
        // calisirken JVM icinden cozumleme patliyordu. JDK'nin kendi
        // (sistem resolver'ini kullanan, curl ile ayni davranan) blocking
        // resolver'ina geciyoruz, bu sorunu tamamen atlatir.
        HttpClient httpClient = HttpClient.create(provider)
                .resolver(DefaultAddressResolverGroup.INSTANCE);

        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
