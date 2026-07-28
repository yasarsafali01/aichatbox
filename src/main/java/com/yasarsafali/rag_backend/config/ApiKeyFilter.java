package com.yasarsafali.rag_backend.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-API-Key";

    @Value("${security.api-key.chroma}")
    private String chromaApiKey;

    @Value("${security.api-key.admin}")
    private String adminApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        boolean isChromaEndpoint = path.startsWith("/rag") || path.startsWith("/locate");
        String requiredKey = isChromaEndpoint ? chromaApiKey : adminApiKey;

        String providedKey = request.getHeader(HEADER_NAME);

        if (!requiredKey.equals(providedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Geçersiz veya eksik X-API-Key\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
