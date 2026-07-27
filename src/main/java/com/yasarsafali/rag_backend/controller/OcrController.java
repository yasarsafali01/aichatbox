package com.yasarsafali.rag_backend.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yasarsafali.rag_backend.dto.quote.Brans;
import com.yasarsafali.rag_backend.service.ocr.FieldExtractor;
import com.yasarsafali.rag_backend.service.ocr.OcrService;

@RestController
@RequestMapping("/ocr")
public class OcrController {

    private final OcrService ocrService;
    private final FieldExtractor fieldExtractor;

    public OcrController(OcrService ocrService, FieldExtractor fieldExtractor) {
        this.ocrService = ocrService;
        this.fieldExtractor = fieldExtractor;
    }

    public record OcrResponse(Map<String, String> fields, String rawTextPreview) {
    }

    @PostMapping(value = "/extract", consumes = "multipart/form-data")
    public OcrResponse extract(@RequestParam("file") MultipartFile file, @RequestParam Brans brans) {
        String rawText;
        try {
            rawText = ocrService.extractText(file);
        } catch (Throwable e) {
            // Belge okunamasa (Tesseract native hatası dahil) bile akış soru sormadan
            // tamamlanabilsin diye FieldExtractor'ın varsayılan değerlerine düşülür.
            rawText = "";
        }
        Map<String, String> fields = fieldExtractor.extract(brans, rawText);
        String preview = rawText.isBlank()
                ? "Belgeden metin okunamadı, alanlar tahmini değerlerle dolduruldu."
                : (rawText.length() > 300 ? rawText.substring(0, 300) + "..." : rawText);
        return new OcrResponse(fields, preview);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleError(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
