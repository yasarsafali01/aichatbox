package com.yasarsafali.rag_backend.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TopicRegistry {

    private final Set<String> topics = Collections.synchronizedSet(new LinkedHashSet<>());

    @Value("${chroma.initial-topics:}")
    public void loadInitialTopics(String raw) {
        if (raw == null || raw.isBlank()) return;
        Arrays.stream(raw.split(","))
              .map(String::trim)
              .filter(s -> !s.isBlank())
              .forEach(topics::add);
    }

    public void register(String topic) {
        topics.add(topic);
    }

    public Set<String> getTopics() {
        return Collections.unmodifiableSet(topics);
    }
}
