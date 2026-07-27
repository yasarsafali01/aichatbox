package com.yasarsafali.rag_backend.dto.quote;

public record QuoteStepResponse(
        String sessionId,
        Brans brans,
        boolean completed,
        String fieldKey,
        String question,
        int stepIndex,
        int totalSteps,
        String error,
        QuoteResult quote
) {
}
