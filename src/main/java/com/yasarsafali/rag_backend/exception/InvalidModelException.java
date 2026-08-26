package com.yasarsafali.rag_backend.exception;

import java.util.List;

public class InvalidModelException extends RuntimeException {

    public InvalidModelException(String requestedModel, List<String> availableModels) {
        super("Geçersiz model: \"" + requestedModel + "\". Kullanılabilir modeller: "
                + String.join(", ", availableModels));
    }
}
