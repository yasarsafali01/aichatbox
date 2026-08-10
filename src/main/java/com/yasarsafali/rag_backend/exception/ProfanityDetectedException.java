package com.yasarsafali.rag_backend.exception;

public class ProfanityDetectedException extends RuntimeException {

    public ProfanityDetectedException() {
        super("Metin küfür veya argo içerdiği için işlenemedi.");
    }
}
