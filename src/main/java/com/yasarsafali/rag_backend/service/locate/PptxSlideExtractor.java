package com.yasarsafali.rag_backend.service.locate;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Service;

@Service
public class PptxSlideExtractor implements LocatableExtractor {

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".pptx") || name.endsWith(".ppt");
    }

    @Override
    public List<LocatableUnit> extract(File file) {
        String name = file.getName().toLowerCase();
        try {
            return name.endsWith(".pptx") ? extractPptx(file) : extractPpt(file);
        } catch (Exception e) {
            throw new RuntimeException("Sunum okuma hatası: " + file.getAbsolutePath(), e);
        }
    }

    private List<LocatableUnit> extractPptx(File file) throws Exception {
        List<LocatableUnit> units = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file);
             XMLSlideShow slideShow = new XMLSlideShow(in)) {
            List<XSLFSlide> slides = slideShow.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (XSLFShape shape : slides.get(i).getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        sb.append(textShape.getText()).append("\n");
                    }
                }
                units.add(new LocatableUnit("Slayt " + (i + 1), sb.toString()));
            }
        }
        return units;
    }

    private List<LocatableUnit> extractPpt(File file) throws Exception {
        List<LocatableUnit> units = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file);
             HSLFSlideShow slideShow = new HSLFSlideShow(in)) {
            List<HSLFSlide> slides = slideShow.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (HSLFShape shape : slides.get(i).getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        sb.append(textShape.getText()).append("\n");
                    }
                }
                units.add(new LocatableUnit("Slayt " + (i + 1), sb.toString()));
            }
        }
        return units;
    }
}
