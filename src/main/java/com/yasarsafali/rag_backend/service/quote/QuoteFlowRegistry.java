package com.yasarsafali.rag_backend.service.quote;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.yasarsafali.rag_backend.dto.quote.Brans;
import com.yasarsafali.rag_backend.dto.quote.QuoteField;

@Component
public class QuoteFlowRegistry {

    private final Map<Brans, List<QuoteField>> flows = Map.of(
            Brans.KASKO, List.of(
                    new QuoteField("plaka", "Aracınızın plakasını yazar mısınız? (örn: 34 ABC 123)",
                            Pattern.compile("^\\d{2}\\s?[A-ZÇĞİÖŞÜ]{1,3}\\s?\\d{2,4}$", Pattern.CASE_INSENSITIVE),
                            "Örnek plaka formatı: 34 ABC 123"),
                    new QuoteField("marka", "Aracınızın markası nedir? (örn: Toyota)",
                            Pattern.compile("^[A-Za-zÇĞİÖŞÜçğıöşü\\s]{2,30}$"),
                            "Sadece harflerden oluşan bir marka adı girin"),
                    new QuoteField("model", "Model adı nedir? (örn: Corolla)",
                            Pattern.compile("^[A-Za-z0-9ÇĞİÖŞÜçğıöşü\\s]{1,30}$"),
                            "Model adını girin"),
                    new QuoteField("yil", "Aracın model yılı nedir? (örn: 2021)",
                            Pattern.compile("^(19|20)\\d{2}$"),
                            "4 haneli bir yıl girin, örn: 2021"),
                    new QuoteField("kullanim", "Aracınız bireysel mi yoksa ticari amaçla mı kullanılıyor? (Bireysel/Ticari)",
                            Pattern.compile("^(bireysel|ticari)$", Pattern.CASE_INSENSITIVE),
                            "\"Bireysel\" veya \"Ticari\" yazın")
            ),
            Brans.KONUT, List.of(
                    new QuoteField("adres", "Konutunuzun bulunduğu il/ilçe nedir? (örn: İstanbul/Kadıköy)",
                            Pattern.compile("^[A-Za-zÇĞİÖŞÜçğıöşü\\s/]{2,60}$"),
                            "İl/ilçe bilgisini girin"),
                    new QuoteField("m2", "Konutunuzun yüzölçümü kaç m2dir? (örn: 120)",
                            Pattern.compile("^\\d{2,4}$"),
                            "Sadece rakamla m2 girin, örn: 120"),
                    new QuoteField("yapimYili", "Binanın yapım yılı nedir? (örn: 2005)",
                            Pattern.compile("^(19|20)\\d{2}$"),
                            "4 haneli bir yıl girin, örn: 2005"),
                    new QuoteField("yapiTipi", "Yapı tipi nedir? (Betonarme/Ahşap/Diğer)",
                            Pattern.compile("^(betonarme|ahşap|ahsap|diğer|diger)$", Pattern.CASE_INSENSITIVE),
                            "\"Betonarme\", \"Ahşap\" veya \"Diğer\" yazın")
            )
    );

    public List<QuoteField> fieldsFor(Brans brans) {
        return flows.get(brans);
    }
}
