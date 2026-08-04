# aichatbox — Proje Mimarisi ve Çalışma Mantığı

Bu doküman, **aichatbox** projesinin uçtan uca ne iş yaptığını, hangi bileşenlerden oluştuğunu ve bu bileşenlerin birbirine nasıl bağlandığını anlatır. Kurulum adımları için bkz. **[KURULUM.md](KURULUM.md)**.

---

## 1. Projenin Amacı

aichatbox, kurum içi belgeler (PDF, Word, Excel, PowerPoint, görseller) üzerinde çalışan, **Türkçe** yanıt veren bir **RAG (Retrieval-Augmented Generation)** sistemidir. Tamamen yerel (self-hosted) LLM altyapısı kullanır — hiçbir belge veya soru dışarıya (OpenAI vb. bulut servislerine) gönderilmez.

Uygulama iki farklı ihtiyacı karşılamak üzere **iki ayrı arama hattı (pipeline)** sunar:

1. **Genel Soru-Cevap (`/rag/ask`)** — "Bu konuda ne biliyoruz?" tarzı sorulara, ilgili belge parçalarından yararlanarak LLM'in ürettiği doğal dilde bir cevap döner.
2. **Konum Bulma (`/locate`)** — "Bu bilgi hangi dosyada, hangi sayfada/paragrafta/slaytta geçiyor?" sorusuna, LLM çağırmadan, doğrudan ilgili dosya + konum + tıklanabilir bağlantı listesi döner.

Bu ayrımın nedeni Bölüm 5'te ayrıntılı açıklanmıştır.

---

## 2. Üst Düzey Mimari

```
                         ┌─────────────────────┐
                         │   İstemci (curl /     │
                         │  frontend / Postman)  │
                         └──────────┬───────────┘
                                    │ HTTP + X-API-Key
                                    ▼
                         ┌─────────────────────┐
                         │   ApiKeyFilter        │  ← her isteği denetler
                         └──────────┬───────────┘
                                    ▼
        ┌───────────────────────────────────────────────────┐
        │                Spring MVC Controller'lar            │
        │  Home / EmbeddingController / OcrController /       │
        │  BenchmarkController / RagController / LocateController │
        └───────────────────────────────────────────────────┘
                     │              │                │
                     ▼              ▼                ▼
        ┌────────────────┐ ┌────────────────┐ ┌──────────────┐
        │  RagService      │ │ LocateService   │ │ OcrService    │
        │  + Ingestion      │ │ + LocateIndexing │ │               │
        │  servisleri       │ │ servisleri        │ │               │
        └────────────────┘ └────────────────┘ └──────────────┘
                     │              │                │
                     ▼              ▼                ▼
        ┌────────────────┐ ┌────────────────┐ ┌──────────────┐
        │  ChromaClient    │ │ LocateChromaClient│ │ Tesseract /   │
        │  (RAG koleksiyonu)│ │ (Locate koleksiyonu)│ │ PDFBox nativ  │
        └────────┬────────┘ └────────┬────────┘ └──────────────┘
                  │                   │
                  └─────────┬─────────┘
                             ▼
                   ┌──────────────────┐        ┌──────────────┐
                   │     ChromaDB       │        │    Ollama     │
                   │ (vektör veritabanı)│        │ (qwen2.5 /    │
                   │  Docker konteyneri │        │ nomic-embed)  │
                   └──────────────────┘        └──────────────┘
```

Tüm embedding üretimi ve LLM metin üretimi **Ollama** üzerinden yapılır; belge vektörleri **ChromaDB**'de saklanır; ilişkisel/oturum verisi (şu an aktif kullanılmıyor) için gömülü **H2** bellek-içi veritabanı hazır bulunur.

---

## 3. Bileşenler ve Sorumlulukları

### 3.1 Giriş katmanı

| Sınıf | Sorumluluk |
|---|---|
| `ApiKeyFilter` (`config/`) | Her isteği (`OPTIONS` hariç) `X-API-Key` header'ına göre doğrular. `/rag/**` ve `/locate/**` için `security.api-key.chroma`, diğer tüm uçlar için `security.api-key.admin` anahtarını bekler. Anahtar tutmuyorsa `401` döner. |
| `WebConfig` (`config/`) | CORS ayarları — `http://localhost:5173` ve `http://localhost:3000` (tipik Vite/React/Next.js geliştirme sunucuları) için `GET/POST/PUT/DELETE/OPTIONS` izni verir. |

### 3.2 Genel amaçlı uçlar

| Sınıf | Uç | Açıklama |
|---|---|---|
| `Home` | `GET /` | Basit sağlık kontrolü. |
| `EmbeddingController` | `GET /embed?text=` | Verilen metni `nomic-embed-text` ile embedding vektörüne çevirip döner (debug/test amaçlı). |
| `OcrController` | `POST /ocr/extract` | Yüklenen dosya `.pdf` ise PDFBox ile, değilse Tesseract OCR ile metne çevrilir. |
| `BenchmarkController` | `POST /benchmark/run` | Bir soru listesini `OllamaClient` üzerinden çalıştırıp her biri için model adı, cevap ve yanıt süresini raporlar — farklı modelleri (bkz. `benchmark.sh`) karşılaştırmak için kullanılır. |

### 3.3 Genel RAG hattı (`/rag`)

**Amaç:** Belgeleri chunk'lara bölüp vektörleştirerek tek bir Chroma koleksiyonuna yükler; soru geldiğinde en alakalı parçaları bulup LLM'e bağlam olarak vererek **doğal dilde bir cevap** ürettirir.

| Sınıf | Rol |
|---|---|
| `RagController` | `/rag/add`, `/rag/ask`, `/rag/load-pdf`, `/rag/load-folder`, `/rag/load-status` uçlarını sağlar. |
| `DocumentIngestionService` | Apache **Tika** (`AutoDetectParser`) ile dosyanın (pdf/doc/docx/xls/xlsx/ppt/pptx) düz metnini çıkarır, 800 karakterlik parçalara (chunk) böler, her parçayı embed edip Chroma'ya ekler. Dosya adından bir "konu" (topic) türetip `TopicRegistry`'ye kaydeder. |
| `BulkIngestionService` | Bir klasördeki tüm desteklenen dosyaları **arka plan thread'inde** sırayla `DocumentIngestionService`'e gönderir; `AtomicInteger` sayaçlarla (`total/processed/succeeded/failed`) ilerleme durumunu tutar. Aynı anda yalnızca bir toplu yükleme çalışabilir. |
| `TopicRegistry` | Yüklenen belgelerden türeyen ve `chroma.initial-topics` ile önceden tanımlı konuların kümesini tutar; kullanıcı bağlam dışı bir soru sorduğunda "şu konularda yardımcı olabilirim" mesajında kullanılır. |
| `OllamaEmbeddingService` | `POST /api/embeddings` (Ollama) çağrısıyla metni embedding vektörüne çevirir. Hem RAG hem Locate hattı tarafından **ortak** kullanılır. |
| `ChromaClient` | Genel RAG koleksiyonuna `add`/`query` REST çağrılarını yapar (`chroma.collection`). |
| `OllamaClient` | `POST /api/generate` (Ollama) ile LLM'den serbest metin üretimi ister (`temperature=0`, `seed=42` — deterministik/tekrarlanabilir cevaplar için). |
| `RagService` | Asıl RAG mantığı: bkz. Bölüm 4.2. |

### 3.4 Konum bulma hattı (`/locate`)

**Amaç:** Belgeleri **sayfa / paragraf / slayt / sayfa (Excel)** gibi anlamlı en küçük birimlere ayırıp ayrı bir Chroma koleksiyonuna, konum bilgisiyle birlikte indeksler. Soru geldiğinde **LLM çağırmadan**, doğrudan en alakalı konumları (dosya adı, konum, tıklanabilir bağlantı, benzerlik skoru) döner.

| Sınıf | Rol |
|---|---|
| `LocateController` | `/locate`, `/locate/index-pdf`, `/locate/index-folder`, `/locate/index-status` uçlarını sağlar. |
| `LocatableExtractor` (arayüz) | "Bu dosyayı destekliyor muyum?" ve "Bu dosyayı konum birimlerine ayır" sözleşmesini tanımlar. Spring, bu arayüzü uygulayan tüm bean'leri otomatik olarak `LocateIndexingService`'e enjekte eder (`List<LocatableExtractor>`). |
| `PdfPageExtractor` | PDF'i **sayfa sayfa** metne çevirir → `"Sayfa N"` konum etiketi. |
| `DocxParagraphExtractor` | `.docx` (Apache POI `XWPFDocument`) ve `.doc` (POI `HWPFDocument`) dosyalarını **paragraf paragraf** ayırır → `"Paragraf N"`. |
| `ExcelSheetExtractor` | `.xlsx`/`.xls` dosyalarını **sayfa (sheet) bazında** hücre metinlerini birleştirerek çıkarır → `"Sayfa: <sheet adı>"`. |
| `PptxSlideExtractor` | `.pptx` (POI `XMLSlideShow`) ve `.ppt` (POI `HSLFSlideShow`) dosyalarını **slayt bazında** metne çevirir → `"Slayt N"`. |
| `LocatableUnit` | `(location, text)` — bir extractor'ın ürettiği tek bir konum biriminin kaydı (örn. `("Sayfa 3", "...metin...")`). |
| `PageChunker` | Her `LocatableUnit`'in metnini, `DocumentIngestionService` ile aynı mantıkla 800 karakterlik parçalara böler (bir sayfa/paragraf/slayt kendi içinde uzunsa birden fazla chunk üretebilir). |
| `LocateIndexingService` | Dosya uzantısına uygun extractor'ı seçer → birimlere ayırır → her birimi chunk'lar → her chunk'ı embed edip **metadata ile birlikte** (`title`, `fileName`, `location`, `url`) `LocateChromaClient`'a ekler. `url`, `locate.pdf.base-url` + URL-encode edilmiş dosya adından üretilir (dosyaya doğrudan tıklanabilir bağlantı). |
| `BulkLocateIndexingService` | `BulkIngestionService` ile birebir aynı desende, bir klasördeki dosyaları arka planda toplu indeksler. |
| `LocateChromaClient` | Ayrı bir Chroma koleksiyonuna (`locate.chroma.collection`) metadata destekli `add`/`query` yapar. |
| `LocateService` | Soru geldiğinde embed edip Chroma'da arar; `locate.distance-threshold` altındaki sonuçları alır; **aynı dosya + aynı konum** için birden fazla chunk eşleşirse yalnızca en yakın (distance'ı en düşük) olanı tutar; sonucu benzerlik skoruna göre sıralayıp `LocateResult` listesi döner. |
| `LocateResult` (dto) | `(title, fileName, location, url, distance)` — API yanıtının birimi. |

---

## 4. Uçtan Uca Veri Akışları

### 4.1 Genel RAG — Belge Yükleme (`POST /rag/load-folder`)

```
Klasör yolu
   │
   ▼
BulkIngestionService.start()
   │  (arka plan thread başlatır, hemen "başlatıldı" döner)
   ▼
Her dosya için DocumentIngestionService.ingest():
   1) Apache Tika ile dosyadan düz metin çıkar
   2) Metni 800 karakterlik parçalara böl (< 30 karakter parçalar atlanır)
   3) Her parça için:
        a) OllamaEmbeddingService.embed(parça)  → embedding vektörü
        b) ChromaClient.add(id, parça, embedding) → Chroma'ya yaz
   4) Dosya adından "konu" türet → TopicRegistry.register()
   │
   ▼
GET /rag/load-status  → { total, processed, succeeded, failed, remaining, lastError }
```

### 4.2 Genel RAG — Soru Sorma (`POST /rag/ask`)

```
Kullanıcı sorusu
   │
   ▼
RagService.ask()
   1) expandQuery(): LLM'e sorunun 2 farklı ifadesini üretmesini iste
      → toplamda 3 varyant sorgu (orijinal + 2 varyant)
   2) Her varyant için:
        embed → ChromaClient.query() → en yakın belge parçaları + mesafeler
   3) distance <= chroma.distance-threshold olan sonuçları topla (tekrarları ele)
   4) Hiç sonuç yoksa:
        → "Üzgünüm, bu konuda bilgim yok. Yalnızca şu konularda yardımcı
           olabilirim: <TopicRegistry içeriği>"
   5) Sonuç varsa:
        → Bulunan parçaları bağlam (context) olarak birleştir
        → LLM'e "yalnızca bu belgelerden yararlanarak Türkçe yanıt ver" 
          talimatıyla gönder (OllamaClient.generate)
        → Üretilen cevabı döndür
```

> **Sorgu genişletme (query expansion)** kullanılmasının nedeni: kullanıcı sorusunun kelimeleri belgedeki ifadeyle birebir örtüşmeyebilir; aynı anlama gelen farklı formülasyonlarla arama yaparak recall (bulma oranı) artırılır.

### 4.3 Konum Bulma — Belge İndeksleme (`POST /locate/index-folder`)

```
Klasör yolu
   │
   ▼
BulkLocateIndexingService.start() (arka plan thread)
   │
   ▼
Her dosya için LocateIndexingService.ingest():
   1) Dosya uzantısına uygun LocatableExtractor'ı seç
      (Pdf → sayfa, Docx/Doc → paragraf, Xls(x) → sheet, Ppt(x) → slayt)
   2) extractor.extract() → List<LocatableUnit> (location + text)
   3) Her unit için PageChunker.chunk() → 800 karakterlik parçalar
   4) Her parça için:
        a) embed(parça)
        b) LocateChromaClient.add(id, parça, embedding, metadata)
           metadata = { title, fileName, location, url }
   │
   ▼
GET /locate/index-status → { total, processed, succeeded, failed, remaining, lastError }
```

### 4.4 Konum Bulma — Sorgu (`POST /locate`)

```
Kullanıcı sorusu
   │
   ▼
LocateService.locate()
   1) embed(soru)
   2) LocateChromaClient.query() → en yakın N sonuç (locate.max-results, vars. 5)
      + her sonucun metadata'sı (title, fileName, location, url)
   3) distance > locate.distance-threshold olanları ele
   4) Aynı (fileName, location) için birden fazla eşleşme varsa
      yalnızca en düşük distance'lı olanı tut
   5) Sonuçları distance'a göre artan sırala
   │
   ▼
List<LocateResult> → JSON yanıt (LLM çağrısı YOK)
```

---

## 5. Neden İki Ayrı Hat? (`/rag` vs `/locate`)

| | `/rag` (Genel RAG) | `/locate` (Konum Bulma) |
|---|---|---|
| Chunk birimi | Dosyanın tamamı → düz 800 karakterlik parçalar | Sayfa/paragraf/slayt/sheet → sonra 800 karakterlik parçalar |
| Konum bilgisi | Yok (yalnızca metin) | Var (`"Sayfa 5"`, `"Paragraf 12"`, `"Slayt 3"` vb.) |
| Chroma koleksiyonu | Ayrı (`chroma.collection`) | Ayrı (`locate.chroma.collection`) |
| LLM çağrısı | **Var** — bağlamdan doğal dilde cevap üretir | **Yok** — ham semantik arama sonucu döner |
| Yanıt hızı | Daha yavaş (embed + LLM generate) | Daha hızlı (yalnızca embed + vektör arama) |
| Kullanım senaryosu | "X konusu hakkında bilgi ver" | "Bu bilgi hangi dosyada/sayfada geçiyor, bana götür" |

İki hat birbirinden bağımsızdır: aynı klasörü hem `/rag/load-folder` hem `/locate/index-folder` ile ayrı ayrı indekslemeniz gerekir; biri diğerini beslemez.

---

## 6. Dış Bağımlılıklar

| Servis | Neden gerekli | Uygulama olmadan ne olur? |
|---|---|---|
| **Ollama** (`localhost:11434`) | Embedding üretimi (`nomic-embed-text`) ve LLM cevap üretimi (`qwen2.5`) | `/rag/ask`, `/rag/add`, `/locate`, `/locate/index-*`, `/embed`, `/benchmark/run` çalışmaz (bağlantı hatası) |
| **ChromaDB** (`localhost:8000`, Docker) | Vektör depolama ve benzerlik araması | `/rag/*` ve `/locate/*` uçları `404`/bağlantı hatası verir |
| **Tesseract OCR** (native) | Görsel dosyalardan (jpg/png) metin çıkarımı | `/ocr/extract` yalnızca PDF için çalışır, görsellerde hata döner |
| **H2** (gömülü) | JPA altyapısı hazır bulundurulur | Şu an aktif bir entity kullanılmıyor; ek kurulum gerekmez |

---

## 7. Teknoloji Yığını

| Katman | Teknoloji |
|---|---|
| Dil / Platform | Java 21, Spring Boot 4.0.6 |
| Web | Spring MVC (`spring-boot-starter-webmvc`), embedded Tomcat |
| AI entegrasyonu | Spring AI 2.0.0-M5 (`spring-ai-starter-model-ollama`), doğrudan Ollama REST API (`WebClient`) |
| Belge ayrıştırma | Apache Tika 3.1.0 (genel RAG), Apache PDFBox 3.0.4 (PDF sayfa bazlı + OCR öncesi), Apache POI 5.4.0 (Word/Excel/PowerPoint) |
| OCR | Tess4j 5.11.0 (Tesseract JNA sarmalayıcısı) |
| Vektör DB | ChromaDB (Docker, REST API v2) |
| Veritabanı | H2 (in-memory) |
| Build | Maven (`mvnw`) |
| Yardımcı | Lombok |

---

## 8. Bilinen Sınırlamalar

- **H2 bellek-içi**: Uygulama yeniden başlatıldığında ilişkisel veriler (şu an aktif kullanılmasa da) sıfırlanır; Chroma verileri Docker volume'da kalıcıdır.
- **CPU-only çıkarım**: Uygun bir GPU (NVIDIA/AMD) yoksa Ollama modelleri CPU üzerinde çalışır; büyük klasörlerin toplu indekslenmesi (`/rag/load-folder`, `/locate/index-folder`) binlerce dosya için **saatler** sürebilir.
- **Tek eşzamanlı toplu işlem**: `BulkIngestionService` ve `BulkLocateIndexingService` aynı anda yalnızca bir toplu yükleme/indeksleme çalıştırır; devam eden bir işlem varken yeni istek "Zaten devam eden bir yükleme var." döner. Çalışan bir toplu işi **durdurmak için API yoktur** — yalnızca uygulamayı yeniden başlatmak (JVM'i sonlandırmak) işlemi keser.
- **Path encoding**: `path` parametrelerinde Türkçe karakter geçen klasör/dosya yolları UTF-8 percent-encoded gönderilmelidir.
- **Sabit koleksiyon ID'leri**: Chroma koleksiyon ID'leri config dosyalarında sabittir; ChromaDB'yi sıfırdan kurduğunuzda bu ID'lerin güncellenmesi gerekir (bkz. KURULUM.md § 3.2).
