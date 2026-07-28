package com.yasarsafali.rag_backend.service.locate;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

@Service
public class ExcelSheetExtractor implements LocatableExtractor {

    @Override
    public boolean supports(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".xlsx") || name.endsWith(".xls");
    }

    @Override
    public List<LocatableUnit> extract(File file) {
        List<LocatableUnit> units = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        try (FileInputStream in = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(in)) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                StringBuilder sb = new StringBuilder();
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (!value.isBlank()) {
                            sb.append(value).append(" ");
                        }
                    }
                    sb.append("\n");
                }
                String text = sb.toString();
                if (!text.isBlank()) {
                    units.add(new LocatableUnit("Sayfa: " + sheet.getSheetName(), text));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Excel dosyası okuma hatası: " + file.getAbsolutePath(), e);
        }

        return units;
    }
}
