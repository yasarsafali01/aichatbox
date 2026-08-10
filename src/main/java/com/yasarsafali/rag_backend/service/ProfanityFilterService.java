package com.yasarsafali.rag_backend.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.exception.ProfanityDetectedException;

/**
 * Kullanıcı girdilerini Türkçe küfür/argo sözcük listesine göre denetler.
 * Liste src/main/resources/profanity/tr-words.txt dosyasından yüklenir.
 */
@Service
public class ProfanityFilterService {

    private static final String WORD_LIST_PATH = "profanity/tr-words.txt";
    private static final Locale TR = Locale.forLanguageTag("tr-TR");
    private static final Pattern REPEATED_CHARS = Pattern.compile("(.)\\1{2,}");
    private static final Pattern NON_LETTER = Pattern.compile("[^a-zçğıöşü\\s]");
    /** Boşluksuz alt-dize taramasında dikkate alınacak en kısa kelime uzunluğu.
     *  "am", "ag", "oc" gibi çok kısa girişler bu taramaya dahil edilmez;
     *  aksi halde neredeyse her mesajda yanlış pozitif üretirler. Kısa kelimeler
     *  yine de tam kelime (token) eşleşmesiyle denetlenir. */
    private static final int MIN_SUBSTRING_MATCH_LENGTH = 4;

    private final Set<String> blockedWords = new HashSet<>();

    public ProfanityFilterService() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(WORD_LIST_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Küfür listesi bulunamadı: " + WORD_LIST_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim().toLowerCase(TR);
                    if (!word.isEmpty() && !word.startsWith("#")) {
                        blockedWords.add(word);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Küfür listesi okunamadı", e);
        }
    }

    public boolean containsProfanity(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalize(text);
        for (String token : normalized.split("\\s+")) {
            if (blockedWords.contains(token)) {
                return true;
            }
        }
        String withoutSpaces = normalized.replace(" ", "");
        for (String word : blockedWords) {
            if (word.length() >= MIN_SUBSTRING_MATCH_LENGTH && word.indexOf(' ') < 0
                    && withoutSpaces.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Metin küfür/argo içeriyorsa {@link ProfanityDetectedException} fırlatır. */
    public void assertClean(String text) {
        if (containsProfanity(text)) {
            throw new ProfanityDetectedException();
        }
    }

    private String normalize(String text) {
        String lower = text.toLowerCase(TR);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            sb.append(leetToLetter(lower.charAt(i)));
        }
        String collapsed = REPEATED_CHARS.matcher(sb).replaceAll("$1$1");
        return NON_LETTER.matcher(collapsed).replaceAll("").trim();
    }

    private char leetToLetter(char c) {
        return switch (c) {
            case '4', '@' -> 'a';
            case '3' -> 'e';
            case '1', '!' -> 'i';
            case '0' -> 'o';
            case '5', '$' -> 's';
            case '7' -> 't';
            default -> c;
        };
    }
}
