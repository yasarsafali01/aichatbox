package com.yasarsafali.rag_backend.service.ocr;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import net.sourceforge.tess4j.Tesseract;

@Service
public class OcrService {

    @Value("${ocr.tessdata-path:}")
    private String tessdataPath;

    @Value("${ocr.language:tur}")
    private String language;

    public String extractText(MultipartFile file) {
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase(Locale.ROOT)
                : "";

        if (filename.endsWith(".pdf")) {
            return extractFromPdf(file);
        }
        return extractFromImage(file);
    }

    private String extractFromPdf(MultipartFile file) {
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new RuntimeException("PDF okunamadı: " + file.getOriginalFilename(), e);
        }
    }

    private String extractFromImage(MultipartFile file) {
        File temp;
        try {
            temp = File.createTempFile("ocr-", suffixOf(file));
            file.transferTo(temp);
        } catch (IOException e) {
            throw new RuntimeException("Belge geçici olarak kaydedilemedi", e);
        }

        try {
            Tesseract tesseract = new Tesseract();
            if (tessdataPath != null && !tessdataPath.isBlank()) {
                tesseract.setDatapath(tessdataPath);
            }
            tesseract.setLanguage(language);
            return tesseract.doOCR(temp);
        } catch (Throwable e) {
            // Tesseract native kütüphanesi eksik/yanlış yapılandırılmışsa TesseractException
            // yerine UnsatisfiedLinkError, NoClassDefFoundError ya da JNA kaynaklı bir
            // java.lang.Error (Invalid memory access) fırlatabilir; hepsi burada yutulur.
            throw new RuntimeException(
                    "Görsel OCR için Tesseract OCR kurulu değil ya da yapılandırılmadı. "
                            + "Lütfen Tesseract-OCR kurun ve 'ocr.tessdata-path' ayarını yapın, "
                            + "ya da belgeyi PDF olarak yükleyin.", e);
        } finally {
            temp.delete();
        }
    }

    private String suffixOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        int dot = name != null ? name.lastIndexOf('.') : -1;
        return dot >= 0 ? name.substring(dot) : ".png";
    }
}
