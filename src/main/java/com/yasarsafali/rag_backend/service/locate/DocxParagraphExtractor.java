package com.yasarsafali.rag_backend.service.locate;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

@Service
public class DocxParagraphExtractor implements LocatableExtractor {

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".docx") || name.endsWith(".doc");
    }

    @Override
    public List<LocatableUnit> extract(File file) {
        String name = file.getName().toLowerCase();
        try {
            return name.endsWith(".docx") ? extractDocx(file) : extractDoc(file);
        } catch (Exception e) {
            throw new RuntimeException("Word belgesi okuma hatası: " + file.getAbsolutePath(), e);
        }
    }

    private List<LocatableUnit> extractDocx(File file) throws Exception {
        List<LocatableUnit> units = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(in)) {
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            int paragraphNo = 0;
            for (XWPFParagraph paragraph : paragraphs) {
                String text = paragraph.getText();
                if (text == null || text.isBlank()) continue;
                paragraphNo++;
                units.add(new LocatableUnit("Paragraf " + paragraphNo, text));
            }
        }
        return units;
    }

    private List<LocatableUnit> extractDoc(File file) throws Exception {
        List<LocatableUnit> units = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(in)) {
            Range range = document.getRange();
            int paragraphNo = 0;
            for (int i = 0; i < range.numParagraphs(); i++) {
                String text = range.getParagraph(i).text();
                if (text == null || text.isBlank()) continue;
                paragraphNo++;
                units.add(new LocatableUnit("Paragraf " + paragraphNo, text));
            }
        }
        return units;
    }
}
