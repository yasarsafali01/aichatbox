package com.yasarsafali.rag_backend.dto.quote;

import java.util.LinkedHashMap;
import java.util.Map;

public class QuoteSession {

    private final String id;
    private final Brans brans;
    private int stepIndex = 0;
    private final Map<String, String> answers = new LinkedHashMap<>();
    private boolean completed = false;

    public QuoteSession(String id, Brans brans) {
        this.id = id;
        this.brans = brans;
    }

    public String getId() {
        return id;
    }

    public Brans getBrans() {
        return brans;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public void advance() {
        stepIndex++;
    }

    public Map<String, String> getAnswers() {
        return answers;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }
}
