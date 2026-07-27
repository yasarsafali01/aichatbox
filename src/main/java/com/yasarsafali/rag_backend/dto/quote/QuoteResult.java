package com.yasarsafali.rag_backend.dto.quote;

import java.util.Map;

public record QuoteResult(double premium, String currency, Map<String, String> breakdown, String disclaimer) {
}
