package com.yasarsafali.rag_backend.dto.quote;

import java.util.regex.Pattern;

public record QuoteField(String key, String question, Pattern validation, String validationHint) {

    public boolean isValid(String answer) {
        return answer != null && validation.matcher(answer.trim()).matches();
    }
}
