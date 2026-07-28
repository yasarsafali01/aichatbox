package com.yasarsafali.rag_backend.service.locate;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class PageChunker {

    private static final int CHUNK_SIZE = 800;
    private static final int MIN_CHUNK_LENGTH = 30;

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String chunk = text.substring(start, end).trim();
            start = end;

            if (chunk.length() < MIN_CHUNK_LENGTH) continue;
            chunks.add(chunk);
        }

        return chunks;
    }
}
