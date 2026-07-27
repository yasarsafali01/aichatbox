package com.yasarsafali.rag_backend.service.quote;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.yasarsafali.rag_backend.dto.quote.Brans;
import com.yasarsafali.rag_backend.dto.quote.QuoteResult;

@Component
public class PricingEngine {

    private static final String DISCLAIMER =
            "Bu, bağlayıcı olmayan gösterge niteliğinde bir teklif tutarıdır. Kesin fiyat için acente onayı gereklidir.";

    public QuoteResult calculate(Brans brans, Map<String, String> answers) {
        return switch (brans) {
            case KASKO -> kasko(answers);
            case KONUT -> konut(answers);
        };
    }

    private QuoteResult kasko(Map<String, String> answers) {
        int yil = Integer.parseInt(answers.get("yil"));
        int age = Math.max(0, Year.now().getValue() - yil);
        boolean ticari = "ticari".equalsIgnoreCase(answers.get("kullanim"));

        double ageFactor = age <= 2 ? 1.4 : age <= 7 ? 1.1 : age <= 15 ? 0.8 : 0.6;
        double kullanimFactor = ticari ? 1.3 : 1.0;
        double basePrice = 3000.0;
        double premium = round(basePrice * ageFactor * kullanimFactor);

        Map<String, String> breakdown = new LinkedHashMap<>();
        breakdown.put("Baz prim", "%.0f TL".formatted(basePrice));
        breakdown.put("Araç yaşı (%d yıl) katsayısı".formatted(age), "x%.2f".formatted(ageFactor));
        breakdown.put("Kullanım tipi (%s) katsayısı".formatted(ticari ? "Ticari" : "Bireysel"), "x%.2f".formatted(kullanimFactor));

        return new QuoteResult(premium, "TRY", breakdown, DISCLAIMER);
    }

    private QuoteResult konut(Map<String, String> answers) {
        int m2 = Integer.parseInt(answers.get("m2"));
        int yapimYili = Integer.parseInt(answers.get("yapimYili"));
        int age = Math.max(0, Year.now().getValue() - yapimYili);
        String yapiTipiRaw = answers.get("yapiTipi");
        String yapiTipi = normalize(yapiTipiRaw);

        double yapiFactor = switch (yapiTipi) {
            case "ahsap" -> 1.5;
            case "diger" -> 1.2;
            default -> 1.0; // betonarme
        };
        double yasFactor = age <= 5 ? 0.9 : age <= 20 ? 1.0 : 1.2;
        double basePrice = 1500.0 + (m2 * 8.0);
        double premium = round(basePrice * yapiFactor * yasFactor);

        Map<String, String> breakdown = new LinkedHashMap<>();
        breakdown.put("Baz prim (%d m2)".formatted(m2), "%.0f TL".formatted(basePrice));
        breakdown.put("Yapı tipi (%s) katsayısı".formatted(yapiTipiRaw), "x%.2f".formatted(yapiFactor));
        breakdown.put("Bina yaşı (%d yıl) katsayısı".formatted(age), "x%.2f".formatted(yasFactor));

        return new QuoteResult(premium, "TRY", breakdown, DISCLAIMER);
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.forLanguageTag("tr"))
                .replace("ı", "i").replace("ş", "s").replace("ğ", "g")
                .replace("ç", "c").replace("ö", "o").replace("ü", "u");
    }

    private double round(double v) {
        return Math.round(v / 10.0) * 10.0;
    }
}
