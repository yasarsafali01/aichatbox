package com.yasarsafali.rag_backend.service.ocr;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.yasarsafali.rag_backend.dto.quote.Brans;

@Component
public class FieldExtractor {

    private static final Pattern PLAKA = Pattern.compile("\\b\\d{2}\\s?[A-ZÇĞİÖŞÜ]{1,3}\\s?\\d{2,4}\\b");
    private static final Pattern YIL = Pattern.compile("\\b(?:19|20)\\d{2}\\b");
    private static final Pattern M2 = Pattern.compile("(\\d{2,4})\\s?(m2|m²|metrekare)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKA = Pattern.compile("(?im)^.*marka.*?:\\s*([A-Za-zÇĞİÖŞÜçğıöşü]+)\\s*$");
    private static final Pattern MODEL = Pattern.compile("(?im)^.*model.*?:\\s*([A-Za-z0-9ÇĞİÖŞÜçğıöşü ]+)\\s*$");
    private static final Pattern KULLANIM = Pattern.compile("(?i)\\b(bireysel|hususi|ticari)\\b");
    private static final Pattern YAPI_TIPI = Pattern.compile("(?i)\\b(betonarme|ahşap|ahsap|diğer|diger)\\b");

    /**
     * Belgeden metin çıkarılamasa ya da beklenen alanlar bulunamasa bile,
     * teklif akışı soru sormadan tamamlanabilsin diye eksik alanlar makul
     * varsayılan değerlerle doldurulur.
     */
    public Map<String, String> extract(Brans brans, String rawText) {
        Map<String, String> fields = new LinkedHashMap<>();
        String text = rawText == null ? "" : rawText;

        switch (brans) {
            case KASKO -> {
                find(PLAKA, text).ifPresent(v -> fields.put("plaka", v.toUpperCase()));
                find(MARKA, text).ifPresent(v -> fields.put("marka", v.trim()));
                find(MODEL, text).ifPresent(v -> fields.put("model", v.trim()));
                find(YIL, text).ifPresent(v -> fields.put("yil", v));
                extractKullanim(text).ifPresent(v -> fields.put("kullanim", v));
                applyKaskoDefaults(fields);
            }
            case KONUT -> {
                find(M2, text).ifPresent(v -> fields.put("m2", v));
                find(YIL, text).ifPresent(v -> fields.put("yapimYili", v));
                find(YAPI_TIPI, text).ifPresent(v -> fields.put("yapiTipi", v));
                applyKonutDefaults(fields);
            }
        }
        return fields;
    }

    private void applyKaskoDefaults(Map<String, String> fields) {
        fields.putIfAbsent("plaka", "00 XX 0000");
        fields.putIfAbsent("marka", "Belirtilmedi");
        fields.putIfAbsent("model", "Belirtilmedi");
        fields.putIfAbsent("yil", String.valueOf(Year.now().getValue() - 5));
        fields.putIfAbsent("kullanim", "Bireysel");
    }

    private void applyKonutDefaults(Map<String, String> fields) {
        fields.putIfAbsent("adres", "Belirtilmedi");
        fields.putIfAbsent("m2", "100");
        fields.putIfAbsent("yapimYili", String.valueOf(Year.now().getValue() - 15));
        fields.putIfAbsent("yapiTipi", "Betonarme");
    }

    private Optional<String> extractKullanim(String text) {
        Matcher matcher = KULLANIM.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        boolean ticari = "ticari".equalsIgnoreCase(matcher.group());
        return Optional.of(ticari ? "Ticari" : "Bireysel");
    }

    private Optional<String> find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
        }
        return Optional.empty();
    }
}
