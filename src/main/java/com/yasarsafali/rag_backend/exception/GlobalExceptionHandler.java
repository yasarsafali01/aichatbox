package com.yasarsafali.rag_backend.exception;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProfanityDetectedException.class)
    public ResponseEntity<String> handleProfanity(ProfanityDetectedException e) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
    }
}
