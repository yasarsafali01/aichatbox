package com.yasarsafali.rag_backend.service.locate;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
public class PdfPageExtractor implements LocatableExtractor {

    @Override
    public boolean supports(File file) {
        return file.getName().toLowerCase().endsWith(".pdf");
    }

    @Override
    public List<LocatableUnit> extract(File file) {
        List<LocatableUnit> units = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                units.add(new LocatableUnit("Sayfa " + page, stripper.getText(document)));
            }
        } catch (Exception e) {
            throw new RuntimeException("PDF okuma hatası: " + file.getAbsolutePath(), e);
        }

        return units;
    }
}
