# aichatbox — Proje Mimarisi ve Çalışma Mantığı

Bu doküman, **aichatbox** projesinin uçtan uca ne iş yaptığını, hangi bileşenlerden oluştuğunu ve bu bileşenlerin birbirine nasıl bağlandığını anlatır. Kurulum adımları için bkz. **[KURULUM.md](KURULUM.md)**.

---

## 1. Projenin Amacı

aichatbox, kurum içi belgeler (PDF, Word, Excel, PowerPoint) üzerinde çalışan, **Türkçe** yanıt veren bir **RAG (Retrieval-Augmented Generation)** sistemidir. Tamamen yerel (self-hosted) LLM altyapısı kullanır — hiçbir belge veya soru dışarıya (OpenAI vb. bulut servislerine) gönderilmez.

Belgelerin kaynağı, yerel bir klasör **değil**, kurum içi bir **External API**'dir ([EXTERNAL_API_GUIDE.md](EXTERNAL_API_GUIDE.md)): `/sync/run` tetiklendiğinde bu API'deki doküman değişiklikleri (yükleme/güncelleme/silme) taranır, ilgili dosyalar indirilip indekslenir.

Uygulama iki farklı ihtiyacı karşılamak üzere **iki ayrı arama hattı (pipeline)** sunar:

1. **Genel Soru-Cevap (`/rag/ask`)** — "Bu konuda ne biliyoruz?" tarzı sorulara, ilgili belge parçalarından yararlanarak LLM'in ürettiği doğal dilde bir cevap döner.
2. **Konum Bulma (`/locate`)** — "Bu bilgi hangi dosyada, hangi sayfada/paragrafta/slaytta geçiyor?" sorusuna, LLM çağırmadan, doğrudan ilgili dosya + konum + tıklanabilir bağlantı listesi döner.

Her iki hat da **aynı senkronizasyon sürecinden** beslenir (bkz. Bölüm 4.1); ayrı ayrı yükleme yapmaya gerek yoktur.

Bu ayrımın nedeni Bölüm 5'te ayrıntılı açıklanmıştır.

---

## 2. Üst Düzey Mimari

```
   ┌──────────────────────┐                 ┌─────────────────────┐
   │  Kurum içi External   │                 │   İstemci (curl /     │
   │  API (döküman kaynağı)│                 │  frontend / Postman)  │
   └──────────┬───────────┘                 └──────────┬───────────┘
              │ GET /documents/changes                  │ HTTP + X-API-Key
              │ (cdn_url'den indirme)                   ▼
              │                              ┌─────────────────────┐
              │                              │   ApiKeyFilter        │  ← her isteği denetler
              │                              └──────────┬───────────┘
              │                                         ▼
              │              ┌───────────────────────────────────────────────────┐
              │              │                Spring MVC Controller'lar            │
              │              │  Home / EmbeddingController / OcrController /       │
              │              │  BenchmarkController / RagController /              │
              │              │  LocateController / SyncController                  │
              │              └───────────────────────────────────────────────────┘
              │                       │              │              │
              ▼                       ▼              ▼              ▼
   ┌───────────────────┐   ┌────────────────┐ ┌────────────────┐ ┌──────────────┐
   │ ExternalApiClient   │   │  RagService      │ │ LocateService   │ │ OcrService    │
   │ ExternalDocument     │──▶│  + Ingestion      │ │ + LocateIndexing │ │               │
   │ SyncService           │   │  servisleri       │ │ servisleri        │ │               │
   └───────────────────┘   └────────────────┘ └────────────────┘ └──────────────┘
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

Tüm embedding üretimi ve LLM metin üretimi **Ollama** üzerinden yapılır; belge vektörleri **ChromaDB**'de saklanır; belgelerin kendisi **External API**'den çekilir; ilişkisel/oturum verisi (şu an aktif kullanılmıyor) için gömülü **H2** bellek-içi veritabanı hazır bulunur.

---

## 3. Bileşenler ve Sorumlulukları

### 3.1 Giriş katmanı

| Sınıf | Sorumluluk |
|---|---|
| `ApiKeyFilter` (`config/`) | Her isteği (`OPTIONS` hariç) `X-API-Key` header'ına göre doğrular. `/rag/**` ve `/locate/**` için `security.api-key.chroma`, diğer tüm uçlar (`/sync/**` dahil) için `security.api-key.admin` anahtarını bekler. Anahtar tutmuyorsa `401` döner. |
| `WebConfig` (`config/`) | CORS ayarları — `http://localhost:5173` ve `http://localhost:3000` (tipik Vite/React/Next.js geliştirme sunucuları) için `GET/POST/PUT/DELETE/OPTIONS` izni verir. |

### 3.2 Genel amaçlı uçlar

| Sınıf | Uç | Açıklama |
|---|---|---|
| `Home` | `GET /` | Basit sağlık kontrolü. |
| `EmbeddingController` | `GET /embed?text=` | Verilen metni `nomic-embed-text` ile embedding vektörüne çevirip döner (debug/test amaçlı). |
| `OcrController` | `POST /ocr/extract` | Yüklenen dosya `.pdf` ise PDFBox ile, değilse Tesseract OCR ile metne çevrilir. |
| `BenchmarkController` | `POST /benchmark/run` | Bir soru listesini `OllamaClient` üzerinden çalıştırıp her biri için model adı, cevap ve yanıt süresini raporlar — farklı modelleri (bkz. `benchmark.sh`) karşılaştırmak için kullanılır. |

### 3.3 External API senkronizasyon hattı (`/sync`)

**Amaç:** Belgelerin **tek kaynağı** olan kurum içi External API'yi tarayıp değişiklikleri (yükleme/güncelleme/silme) hem genel RAG hem de konum bulma koleksiyonlarına yansıtmak. Detaylı akış için bkz. Bölüm 4.1.

| Sınıf | Rol |
|---|---|
| `SyncController` | `/sync/run` (senkronizasyonu arka planda başlatır) ve `/sync/status` (ilerleme durumu) uçlarını sağlar. |
| `ExternalApiClient` | External API'nin `GET /documents/changes` ucunu `X-API-Key` ile çağırır; ayrıca `cdn_url`'den dosyayı akış (streaming) olarak geçici bir dosyaya indirir (büyük dosyalarda belleği şişirmemek için). |
| `ExternalDocumentChange` / `ExternalDocumentsChangesResponse` (dto) | External API'nin JSON yanıtının Java karşılığı (`id`, `event_type`, `original_name`, `file_type`, `mime_type`, `file_size`, `cdn_url`, `birim_id`, `birim_name`, `updated_at`, `next_cursor` vb.). `ExternalDocumentChange.toMetadata()`, bu alanların **tamamını** düz bir Chroma metadata map'ine çevirir — bkz. Bölüm 3.6. |
| `ExternalDocumentSyncService` | Asıl senkronizasyon mantığı: bkz. Bölüm 4.1. `AtomicInteger` sayaçlarla ilerleme durumunu tutar; cursor'ı bellekte tutar. Aynı anda yalnızca bir senkronizasyon çalışabilir. |

### 3.4 Genel RAG hattı (`/rag`)

**Amaç:** Belgeleri chunk'lara bölüp vektörleştirerek tek bir Chroma koleksiyonuna yükler; soru geldiğinde en alakalı parçaları bulup LLM'e bağlam olarak vererek **doğal dilde bir cevap** ürettirir.

| Sınıf | Rol |
|---|---|
| `RagController` | `/rag/add`, `/rag/ask` uçlarını sağlar. |
| `DocumentIngestionService` | Apache **Tika** (`AutoDetectParser`) ile indirilen dosyanın (pdf/doc/docx/xls/xlsx/ppt/pptx) düz metnini çıkarır, 800 karakterlik parçalara (chunk) böler, her parçayı embed edip **`item.toMetadata()`'nın tüm alanlarıyla birlikte** (bkz. Bölüm 3.6) Chroma'ya ekler. Dosya adından bir "konu" (topic) türetip `TopicRegistry`'ye kaydeder. Ayrıca `deleteDocument(documentId)` ile bir belgenin tüm chunk'larını Chroma'dan siler. |
| `TopicRegistry` | Yüklenen belgelerden türeyen ve `chroma.initial-topics` ile önceden tanımlı konuların kümesini tutar; kullanıcı bağlam dışı bir soru sorduğunda "şu konularda yardımcı olabilirim" mesajında kullanılır. |
| `OllamaEmbeddingService` | `POST /api/embeddings` (Ollama) çağrısıyla metni embedding vektörüne çevirir. Hem RAG hem Locate hattı tarafından **ortak** kullanılır. |
| `ChromaClient` | Genel RAG koleksiyonuna metadata destekli `add`/`query`/`delete` REST çağrılarını yapar (`chroma.collection`). `delete`, `{"where": {"documentId": ...}}` filtresiyle bir belgenin tüm chunk'larını tek seferde temizler. |
| `OllamaClient` | `POST /api/generate` (Ollama) ile LLM'den serbest metin üretimi ister (`temperature=0`, `seed=42` — deterministik/tekrarlanabilir cevaplar için). |
| `RagService` | Asıl RAG mantığı: bkz. Bölüm 4.2. |

### 3.5 Konum bulma hattı (`/locate`)

**Amaç:** Belgeleri **sayfa / paragraf / slayt / sayfa (Excel)** gibi anlamlı en küçük birimlere ayırıp ayrı bir Chroma koleksiyonuna, konum bilgisiyle birlikte indeksler. Soru geldiğinde **LLM çağırmadan**, doğrudan en alakalı konumları (dosya adı, konum, tıklanabilir bağlantı, benzerlik skoru) döner.

| Sınıf | Rol |
|---|---|
| `LocateController` | Bare `POST /locate` ucunu sağlar. |
| `LocatableExtractor` (arayüz) | "Bu dosyayı destekliyor muyum?" ve "Bu dosyayı konum birimlerine ayır" sözleşmesini tanımlar. Spring, bu arayüzü uygulayan tüm bean'leri otomatik olarak `LocateIndexingService`'e enjekte eder (`List<LocatableExtractor>`). |
| `PdfPageExtractor` | PDF'i **sayfa sayfa** metne çevirir → `"Sayfa N"` konum etiketi. |
| `DocxParagraphExtractor` | `.docx` (Apache POI `XWPFDocument`) ve `.doc` (POI `HWPFDocument`) dosyalarını **paragraf paragraf** ayırır → `"Paragraf N"`. |
| `ExcelSheetExtractor` | `.xlsx`/`.xls` dosyalarını **sayfa (sheet) bazında** hücre metinlerini birleştirerek çıkarır → `"Sayfa: <sheet adı>"`. |
| `PptxSlideExtractor` | `.pptx` (POI `XMLSlideShow`) ve `.ppt` (POI `HSLFSlideShow`) dosyalarını **slayt bazında** metne çevirir → `"Slayt N"`. |
| `LocatableUnit` | `(location, text)` — bir extractor'ın ürettiği tek bir konum biriminin kaydı (örn. `("Sayfa 3", "...metin...")`). |
| `PageChunker` | Her `LocatableUnit`'in metnini, `DocumentIngestionService` ile aynı mantıkla 800 karakterlik parçalara böler (bir sayfa/paragraf/slayt kendi içinde uzunsa birden fazla chunk üretebilir). |
| `LocateIndexingService` | İndirilen dosyayı destekleyen extractor'ı seçer → birimlere ayırır → her birimi chunk'lar → her chunk'ı embed edip **`item.toMetadata()`'nın tüm alanları + `title` + `location`** ile `LocateChromaClient`'a ekler (bkz. Bölüm 3.6). `url`, External API'nin verdiği **`cdn_url`**'dir (dosyaya doğrudan tıklanabilir bağlantı). Ayrıca `deleteDocument(documentId)` ile bir belgenin tüm chunk'larını siler. |
| `LocateChromaClient` | Ayrı bir Chroma koleksiyonuna (`locate.chroma.collection`) metadata destekli `add`/`query`/`delete` yapar. |
| `LocateService` | Soru geldiğinde embed edip Chroma'da arar; `locate.distance-threshold` altındaki sonuçları alır; **aynı dosya + aynı konum** için birden fazla chunk eşleşirse yalnızca en yakın (distance'ı en düşük) olanı tutar; sonucu benzerlik skoruna göre sıralayıp `LocateResult` listesi döner. |
| `LocateResult` (dto) | `(title, fileName, location, url, distance)` — API yanıtının şu anki birimi. Chroma'daki metadata bundan daha zengindir (bkz. Bölüm 3.6); `LocateResult` henüz bu ek alanları dışarı vermiyor. |

### 3.6 Chroma Metadata Şeması (RAG + Locate ortak)

`ExternalDocumentChange.toMetadata()`, External API yanıtındaki **tüm alanları** düz (flat) bir metadata map'ine çevirir — Chroma'nın `where` filtresiyle sorgulanabilsin diye. Hem genel RAG koleksiyonundaki hem de locate koleksiyonundaki **her chunk** bu alanları taşır:

| Metadata anahtarı | Kaynak (External API alanı) | Tip |
|---|---|---|
| `documentId` | `id` | string |
| `eventType` | `event_type` | string (`"upsert"` senkronize edilenler için) |
| `fileName` | `original_name` | string |
| `fileType` | `file_type` | string (örn. `"PDF"`) |
| `mimeType` | `mime_type` | string |
| `fileSize` | `file_size` | number |
| `cdnUrl` | `cdn_url` | string |
| `birimId` | `birim_id` | number (negatif = harici birim) |
| `birimNameTr` | `birim_name.tr` | string |
| `birimNameEn` | `birim_name.en` | string |
| `updatedAt` | `updated_at` | string (RFC3339) |

Locate koleksiyonu ayrıca şu iki alanı ekler (RAG koleksiyonunda yoktur):

| Metadata anahtarı | Açıklama |
|---|---|
| `title` | Dosya adından uzantısız türetilen başlık |
| `location` | `"Sayfa N"` / `"Paragraf N"` / `"Slayt N"` / `"Sayfa: <sheet adı>"` |

> **Not:** `birim_name` API'de `{tr, en}` şeklinde iç içe bir map olarak gelir; Chroma metadata düz (flat) olmak zorunda olduğundan `birimNameTr`/`birimNameEn` olarak iki ayrı alana açılır. Bu alanlar şu an **yalnızca Chroma'da saklanır** — `/rag/ask` ve `/locate` yanıtlarında (henüz) dışarı verilmez; birime/dosya türüne/tarihe göre filtreleme ileride bu metadata üzerinden (`where` filtresi) eklenecektir.

---

## 4. Uçtan Uca Veri Akışları

### 4.1 Belge Senkronizasyonu (`POST /sync/run`) — RAG + Locate ortak kaynağı

```
POST /sync/run
   │  (arka plan thread başlatır, hemen "başlatıldı" döner)
   ▼
ExternalDocumentSyncService.runSync()
   since = bellekteki cursor (yoksa external-api.initial-since)
   │
   ▼
loop:
   1) ExternalApiClient.getChanges(since, limit) → { items[], next_cursor }
   2) items boşsa: since = next_cursor, döngüden çık
   3) items doluysa, her item için:
        a) Önce mevcut chunk'ları temizle (idempotent upsert/silme):
             DocumentIngestionService.deleteDocument(item.id)
             LocateIndexingService.deleteDocument(item.id)
        b) event_type == "deleted" ise → dur, sıradaki item'a geç
        c) Dosya türü desteklenmiyorsa (pdf/doc/docx/xls/xlsx/ppt/pptx
           dışında) → dur, sıradaki item'a geç
        d) ExternalApiClient.download(item.cdn_url) → geçici dosya
        e) DocumentIngestionService.ingest(dosya, item)
           → genel RAG koleksiyonuna chunk'lanıp, item.toMetadata() ile yazılır
        f) LocateIndexingService.ingest(dosya, item)
           → sayfa/paragraf/slayt bazlı locate koleksiyonuna,
             item.toMetadata() + title + location ile yazılır
        g) geçici dosya silinir
   4) since = next_cursor, 1'e dön
   │
   ▼
cursor bellekte güncellenir (kalıcı değildir, bkz. KURULUM.md § 6.1)

GET /sync/status → { running, totalItems, processed, upserted, deleted,
                      skipped, failed, lastError, cursor }
```

> **Neden önce sil, sonra ekle?** External API `event_type: "upsert"` bir belgenin **yeni içerikle güncellendiğini** de ifade edebilir (aynı `id`, farklı `cdn_url`/`updated_at`). Eski chunk sayısı ile yeni chunk sayısı farklı olabileceğinden, güvenli ve idempotent bir upsert için önce `documentId`'ye ait tüm eski chunk'lar silinip ardından güncel içerik yeniden yazılır. Bu, External API kılavuzunun *"Idempotent işle (id bazlı upsert)"* notuyla uyumludur.

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

### 4.3 Konum Bulma — Sorgu (`POST /locate`)

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

İki hat **aynı `/sync/run` çalıştırmasında birlikte** beslenir (bkz. Bölüm 4.1) — External API'den gelen her belge, tek bir senkronizasyon geçişinde hem genel RAG hem de locate koleksiyonuna yazılır; ayrı ayrı tetiklemeye gerek yoktur.

---

## 6. Dış Bağımlılıklar

| Servis | Neden gerekli | Uygulama olmadan ne olur? |
|---|---|---|
| **External API** (`www2.mersin.edu.tr`) | Belgelerin **tek kaynağı** — hangi belgelerin yüklenip/silineceği buradan öğrenilir, dosya içeriği `cdn_url`'den indirilir | `/sync/run` başarısız olur (401/bağlantı hatası); mevcut Chroma verisi etkilenmez ama yeni/güncel belge işlenmez |
| **Ollama** (`localhost:11434`) | Embedding üretimi (`nomic-embed-text`) ve LLM cevap üretimi (`qwen2.5`) | `/rag/ask`, `/rag/add`, `/locate`, `/sync/run`, `/embed`, `/benchmark/run` çalışmaz (bağlantı hatası) |
| **ChromaDB** (`localhost:8000`, Docker) | Vektör depolama ve benzerlik araması | `/rag/*`, `/locate/*` ve `/sync/run` uçları `404`/bağlantı hatası verir |
| **Tesseract OCR** (native) | Görsel dosyalardan (jpg/png) metin çıkarımı | `/ocr/extract` yalnızca PDF için çalışır, görsellerde hata döner |
| **H2** (gömülü) | JPA altyapısı hazır bulundurulur | Şu an aktif bir entity kullanılmıyor; ek kurulum gerekmez |

---

## 7. Teknoloji Yığını

| Katman | Teknoloji |
|---|---|
| Dil / Platform | Java 21, Spring Boot 4.0.6 |
| Web | Spring MVC (`spring-boot-starter-webmvc`), embedded Tomcat |
| AI entegrasyonu | Spring AI 2.0.0-M5 (`spring-ai-starter-model-ollama`), doğrudan Ollama REST API (`WebClient`) |
| Belge kaynağı | Kurum içi External API (REST, `X-API-Key`), `WebClient` ile cursor tabanlı senkronizasyon + akış (streaming) dosya indirme |
| Belge ayrıştırma | Apache Tika 3.1.0 (genel RAG), Apache PDFBox 3.0.4 (PDF sayfa bazlı + OCR öncesi), Apache POI 5.4.0 (Word/Excel/PowerPoint) |
| OCR | Tess4j 5.11.0 (Tesseract JNA sarmalayıcısı) |
| Vektör DB | ChromaDB (Docker, REST API v2) |
| Veritabanı | H2 (in-memory) |
| Build | Maven (`mvnw`) |
| Yardımcı | Lombok |

---

## 8. Bilinen Sınırlamalar

- **H2 bellek-içi**: Uygulama yeniden başlatıldığında ilişkisel veriler (şu an aktif kullanılmasa da) sıfırlanır; Chroma verileri Docker volume'da kalıcıdır.
- **Senkronizasyon cursor'ı bellekte**: `ExternalDocumentSyncService` cursor'ı (`next_cursor`) kalıcı depoda tutmaz; uygulama yeniden başladığında `external-api.initial-since`'ten itibaren yeniden tarar. `documentId` bazlı sil-sonra-ekle deseni sayesinde bu güvenlidir (veri bozulmaz) ama gereksiz yeniden işleme anlamına gelir.
- **CPU-only çıkarım**: Uygun bir GPU (NVIDIA/AMD) yoksa Ollama modelleri CPU üzerinde çalışır; External API'den çok sayıda belge geldiğinde `/sync/run` **saatler** sürebilir.
- **Tek eşzamanlı senkronizasyon**: `ExternalDocumentSyncService` aynı anda yalnızca bir senkronizasyon çalıştırır; devam eden bir işlem varken yeni istek "Zaten devam eden bir senkronizasyon var." döner. Çalışan bir senkronizasyonu **durdurmak için API yoktur** — yalnızca uygulamayı yeniden başlatmak (JVM'i sonlandırmak) işlemi keser.
- **`/sync/run` otomatik değil**: Periyodik `@Scheduled` polling bilinçli olarak eklenmedi; senkronizasyon yalnızca `POST /sync/run` ile manuel tetiklenir (örn. dışarıdan bir cron/orkestrasyon aracıyla).
- **Sabit koleksiyon ID'leri**: Chroma koleksiyon ID'leri config dosyalarında sabittir; ChromaDB'yi sıfırdan kurduğunuzda bu ID'lerin güncellenmesi gerekir (bkz. KURULUM.md § 3.2).

# aichatbox — Proje Mimarisi ve Çalışma Mantığı

Bu doküman, **aichatbox** projesinin uçtan uca ne iş yaptığını, hangi bileşenlerden oluştuğunu ve bu bileşenlerin birbirine nasıl bağlandığını anlatır. Kurulum adımları için bkz. **[KURULUM.md](KURULUM.md)**.

---

## 1. Projenin Amacı

aichatbox, kurum içi belgeler (PDF, Word, Excel, PowerPoint) üzerinde çalışan, **Türkçe** yanıt veren bir **RAG (Retrieval-Augmented Generation)** sistemidir. Tamamen yerel (self-hosted) LLM altyapısı kullanır — hiçbir belge veya soru dışarıya (OpenAI vb. bulut servislerine) gönderilmez.

Belgelerin kaynağı, yerel bir klasör **değil**, kurum içi bir **External API**'dir ([EXTERNAL_API_GUIDE.md](EXTERNAL_API_GUIDE.md)): `/sync/run` tetiklendiğinde bu API'deki doküman değişiklikleri (yükleme/güncelleme/silme) taranır, ilgili dosyalar indirilip indekslenir.

Uygulama iki farklı ihtiyacı karşılamak üzere **iki ayrı arama hattı (pipeline)** sunar:

1. **Genel Soru-Cevap (`/rag/ask`)** — "Bu konuda ne biliyoruz?" tarzı sorulara, ilgili belge parçalarından yararlanarak LLM'in ürettiği doğal dilde bir cevap döner.
2. **Konum Bulma (`/locate`)** — "Bu bilgi hangi dosyada, hangi sayfada/paragrafta/slaytta geçiyor?" sorusuna, LLM çağırmadan, doğrudan ilgili dosya + konum + tıklanabilir bağlantı listesi döner.

Her iki hat da **aynı senkronizasyon sürecinden** beslenir (bkz. Bölüm 4.1); ayrı ayrı yükleme yapmaya gerek yoktur.

Bu ayrımın nedeni Bölüm 5'te ayrıntılı açıklanmıştır.

---

## 2. Üst Düzey Mimari

```
   ┌──────────────────────┐                 ┌─────────────────────┐
   │  Kurum içi External   │                 │   İstemci (curl /     │
   │  API (döküman kaynağı)│                 │  frontend / Postman)  │
   └──────────┬───────────┘                 └──────────┬───────────┘
              │ GET /documents/changes                  │ HTTP + X-API-Key
              │ (cdn_url'den indirme)                   ▼
              │                              ┌─────────────────────┐
              │                              │   ApiKeyFilter        │  ← her isteği denetler
              │                              └──────────┬───────────┘
              │                                         ▼
              │              ┌───────────────────────────────────────────────────┐
              │              │                Spring MVC Controller'lar            │
              │              │  Home / EmbeddingController / OcrController /       │
              │              │  BenchmarkController / RagController /              │
              │              │  LocateController / SyncController                  │
              │              └───────────────────────────────────────────────────┘
              │                       │              │              │
              ▼                       ▼              ▼              ▼
   ┌───────────────────┐   ┌────────────────┐ ┌────────────────┐ ┌──────────────┐
   │ ExternalApiClient   │   │  RagService      │ │ LocateService   │ │ OcrService    │
   │ ExternalDocument     │──▶│  + Ingestion      │ │ + LocateIndexing │ │               │
   │ SyncService           │   │  servisleri       │ │ servisleri        │ │               │
   └───────────────────┘   └────────────────┘ └────────────────┘ └──────────────┘
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

Tüm embedding üretimi ve LLM metin üretimi **Ollama** üzerinden yapılır; belge vektörleri **ChromaDB**'de saklanır; belgelerin kendisi **External API**'den çekilir; ilişkisel/oturum verisi (şu an aktif kullanılmıyor) için gömülü **H2** bellek-içi veritabanı hazır bulunur.

---

## 3. Bileşenler ve Sorumlulukları

### 3.1 Giriş katmanı

| Sınıf | Sorumluluk |
|---|---|
| `ApiKeyFilter` (`config/`) | Her isteği (`OPTIONS` hariç) `X-API-Key` header'ına göre doğrular. `/rag/**` ve `/locate/**` için `security.api-key.chroma`, diğer tüm uçlar (`/sync/**` dahil) için `security.api-key.admin` anahtarını bekler. Anahtar tutmuyorsa `401` döner. |
| `WebConfig` (`config/`) | CORS ayarları — `http://localhost:5173` ve `http://localhost:3000` (tipik Vite/React/Next.js geliştirme sunucuları) için `GET/POST/PUT/DELETE/OPTIONS` izni verir. |

### 3.2 Genel amaçlı uçlar

| Sınıf | Uç | Açıklama |
|---|---|---|
| `Home` | `GET /` | Basit sağlık kontrolü. |
| `EmbeddingController` | `GET /embed?text=` | Verilen metni `nomic-embed-text` ile embedding vektörüne çevirip döner (debug/test amaçlı). |
| `OcrController` | `POST /ocr/extract` | Yüklenen dosya `.pdf` ise PDFBox ile, değilse Tesseract OCR ile metne çevrilir. |
| `BenchmarkController` | `POST /benchmark/run` | Bir soru listesini `OllamaClient` üzerinden çalıştırıp her biri için model adı, cevap ve yanıt süresini raporlar — farklı modelleri (bkz. `benchmark.sh`) karşılaştırmak için kullanılır. |

### 3.3 External API senkronizasyon hattı (`/sync`)

**Amaç:** Belgelerin **tek kaynağı** olan kurum içi External API'yi tarayıp değişiklikleri (yükleme/güncelleme/silme) hem genel RAG hem de konum bulma koleksiyonlarına yansıtmak. Detaylı akış için bkz. Bölüm 4.1.

| Sınıf | Rol |
|---|---|
| `SyncController` | `/sync/run` (senkronizasyonu arka planda başlatır) ve `/sync/status` (ilerleme durumu) uçlarını sağlar. |
| `ExternalApiClient` | External API'nin `GET /documents/changes` ucunu `X-API-Key` ile çağırır; ayrıca `cdn_url`'den dosyayı akış (streaming) olarak geçici bir dosyaya indirir (büyük dosyalarda belleği şişirmemek için). |
| `ExternalDocumentChange` / `ExternalDocumentsChangesResponse` (dto) | External API'nin JSON yanıtının Java karşılığı (`id`, `event_type`, `original_name`, `file_type`, `mime_type`, `file_size`, `cdn_url`, `birim_id`, `birim_name`, `updated_at`, `next_cursor` vb.). `ExternalDocumentChange.toMetadata()`, bu alanların **tamamını** düz bir Chroma metadata map'ine çevirir — bkz. Bölüm 3.6. |
| `ExternalDocumentSyncService` | Asıl senkronizasyon mantığı: bkz. Bölüm 4.1. `AtomicInteger` sayaçlarla ilerleme durumunu tutar; cursor'ı bellekte tutar. Aynı anda yalnızca bir senkronizasyon çalışabilir. |

### 3.4 Genel RAG hattı (`/rag`)

**Amaç:** Belgeleri chunk'lara bölüp vektörleştirerek tek bir Chroma koleksiyonuna yükler; soru geldiğinde en alakalı parçaları bulup LLM'e bağlam olarak vererek **doğal dilde bir cevap** ürettirir.

| Sınıf | Rol |
|---|---|
| `RagController` | `/rag/add`, `/rag/ask` uçlarını sağlar. |
| `DocumentIngestionService` | Apache **Tika** (`AutoDetectParser`) ile indirilen dosyanın (pdf/doc/docx/xls/xlsx/ppt/pptx) düz metnini çıkarır, 800 karakterlik parçalara (chunk) böler, her parçayı embed edip **`item.toMetadata()`'nın tüm alanlarıyla birlikte** (bkz. Bölüm 3.6) Chroma'ya ekler. Dosya adından bir "konu" (topic) türetip `TopicRegistry`'ye kaydeder. Ayrıca `deleteDocument(documentId)` ile bir belgenin tüm chunk'larını Chroma'dan siler. |
| `TopicRegistry` | Yüklenen belgelerden türeyen ve `chroma.initial-topics` ile önceden tanımlı konuların kümesini tutar; kullanıcı bağlam dışı bir soru sorduğunda "şu konularda yardımcı olabilirim" mesajında kullanılır. |
| `OllamaEmbeddingService` | `POST /api/embeddings` (Ollama) çağrısıyla metni embedding vektörüne çevirir. Hem RAG hem Locate hattı tarafından **ortak** kullanılır. |
| `ChromaClient` | Genel RAG koleksiyonuna metadata destekli `add`/`query`/`delete` REST çağrılarını yapar (`chroma.collection`). `delete`, `{"where": {"documentId": ...}}` filtresiyle bir belgenin tüm chunk'larını tek seferde temizler. |
| `OllamaClient` | `POST /api/generate` (Ollama) ile LLM'den serbest metin üretimi ister (`temperature=0`, `seed=42` — deterministik/tekrarlanabilir cevaplar için). |
| `RagService` | Asıl RAG mantığı: bkz. Bölüm 4.2. |

### 3.5 Konum bulma hattı (`/locate`)

**Amaç:** Belgeleri **sayfa / paragraf / slayt / sayfa (Excel)** gibi anlamlı en küçük birimlere ayırıp ayrı bir Chroma koleksiyonuna, konum bilgisiyle birlikte indeksler. Soru geldiğinde **LLM çağırmadan**, doğrudan en alakalı konumları (dosya adı, konum, tıklanabilir bağlantı, benzerlik skoru) döner.

| Sınıf | Rol |
|---|---|
| `LocateController` | Bare `POST /locate` ucunu sağlar. |
| `LocatableExtractor` (arayüz) | "Bu dosyayı destekliyor muyum?" ve "Bu dosyayı konum birimlerine ayır" sözleşmesini tanımlar. Spring, bu arayüzü uygulayan tüm bean'leri otomatik olarak `LocateIndexingService`'e enjekte eder (`List<LocatableExtractor>`). |
| `PdfPageExtractor` | PDF'i **sayfa sayfa** metne çevirir → `"Sayfa N"` konum etiketi. |
| `DocxParagraphExtractor` | `.docx` (Apache POI `XWPFDocument`) ve `.doc` (POI `HWPFDocument`) dosyalarını **paragraf paragraf** ayırır → `"Paragraf N"`. |
| `ExcelSheetExtractor` | `.xlsx`/`.xls` dosyalarını **sayfa (sheet) bazında** hücre metinlerini birleştirerek çıkarır → `"Sayfa: <sheet adı>"`. |
| `PptxSlideExtractor` | `.pptx` (POI `XMLSlideShow`) ve `.ppt` (POI `HSLFSlideShow`) dosyalarını **slayt bazında** metne çevirir → `"Slayt N"`. |
| `LocatableUnit` | `(location, text)` — bir extractor'ın ürettiği tek bir konum biriminin kaydı (örn. `("Sayfa 3", "...metin...")`). |
| `PageChunker` | Her `LocatableUnit`'in metnini, `DocumentIngestionService` ile aynı mantıkla 800 karakterlik parçalara böler (bir sayfa/paragraf/slayt kendi içinde uzunsa birden fazla chunk üretebilir). |
| `LocateIndexingService` | İndirilen dosyayı destekleyen extractor'ı seçer → birimlere ayırır → her birimi chunk'lar → her chunk'ı embed edip **`item.toMetadata()`'nın tüm alanları + `title` + `location`** ile `LocateChromaClient`'a ekler (bkz. Bölüm 3.6). `url`, External API'nin verdiği **`cdn_url`**'dir (dosyaya doğrudan tıklanabilir bağlantı). Ayrıca `deleteDocument(documentId)` ile bir belgenin tüm chunk'larını siler. |
| `LocateChromaClient` | Ayrı bir Chroma koleksiyonuna (`locate.chroma.collection`) metadata destekli `add`/`query`/`delete` yapar. |
| `LocateService` | Soru geldiğinde embed edip Chroma'da arar; `locate.distance-threshold` altındaki sonuçları alır; **aynı dosya + aynı konum** için birden fazla chunk eşleşirse yalnızca en yakın (distance'ı en düşük) olanı tutar; sonucu benzerlik skoruna göre sıralayıp `LocateResult` listesi döner. |
| `LocateResult` (dto) | `(title, fileName, location, url, distance)` — API yanıtının şu anki birimi. Chroma'daki metadata bundan daha zengindir (bkz. Bölüm 3.6); `LocateResult` henüz bu ek alanları dışarı vermiyor. |

### 3.6 Chroma Metadata Şeması (RAG + Locate ortak)

`ExternalDocumentChange.toMetadata()`, External API yanıtındaki **tüm alanları** düz (flat) bir metadata map'ine çevirir — Chroma'nın `where` filtresiyle sorgulanabilsin diye. Hem genel RAG koleksiyonundaki hem de locate koleksiyonundaki **her chunk** bu alanları taşır:

| Metadata anahtarı | Kaynak (External API alanı) | Tip |
|---|---|---|
| `documentId` | `id` | string |
| `eventType` | `event_type` | string (`"upsert"` senkronize edilenler için) |
| `fileName` | `original_name` | string |
| `fileType` | `file_type` | string (örn. `"PDF"`) |
| `mimeType` | `mime_type` | string |
| `fileSize` | `file_size` | number |
| `cdnUrl` | `cdn_url` | string |
| `birimId` | `birim_id` | number (negatif = harici birim) |
| `birimNameTr` | `birim_name.tr` | string |
| `birimNameEn` | `birim_name.en` | string |
| `updatedAt` | `updated_at` | string (RFC3339) |

Locate koleksiyonu ayrıca şu iki alanı ekler (RAG koleksiyonunda yoktur):

| Metadata anahtarı | Açıklama |
|---|---|
| `title` | Dosya adından uzantısız türetilen başlık |
| `location` | `"Sayfa N"` / `"Paragraf N"` / `"Slayt N"` / `"Sayfa: <sheet adı>"` |

> **Not:** `birim_name` API'de `{tr, en}` şeklinde iç içe bir map olarak gelir; Chroma metadata düz (flat) olmak zorunda olduğundan `birimNameTr`/`birimNameEn` olarak iki ayrı alana açılır. Bu alanlar şu an **yalnızca Chroma'da saklanır** — `/rag/ask` ve `/locate` yanıtlarında (henüz) dışarı verilmez; birime/dosya türüne/tarihe göre filtreleme ileride bu metadata üzerinden (`where` filtresi) eklenecektir.

---

## 4. Uçtan Uca Veri Akışları

### 4.1 Belge Senkronizasyonu (`POST /sync/run`) — RAG + Locate ortak kaynağı

```
POST /sync/run
   │  (arka plan thread başlatır, hemen "başlatıldı" döner)
   ▼
ExternalDocumentSyncService.runSync()
   since = bellekteki cursor (yoksa external-api.initial-since)
   │
   ▼
loop:
   1) ExternalApiClient.getChanges(since, limit) → { items[], next_cursor }
   2) items boşsa: since = next_cursor, döngüden çık
   3) items doluysa, her item için:
        a) Önce mevcut chunk'ları temizle (idempotent upsert/silme):
             DocumentIngestionService.deleteDocument(item.id)
             LocateIndexingService.deleteDocument(item.id)
        b) event_type == "deleted" ise → dur, sıradaki item'a geç
        c) Dosya türü desteklenmiyorsa (pdf/doc/docx/xls/xlsx/ppt/pptx
           dışında) → dur, sıradaki item'a geç
        d) ExternalApiClient.download(item.cdn_url) → geçici dosya
        e) DocumentIngestionService.ingest(dosya, item)
           → genel RAG koleksiyonuna chunk'lanıp, item.toMetadata() ile yazılır
        f) LocateIndexingService.ingest(dosya, item)
           → sayfa/paragraf/slayt bazlı locate koleksiyonuna,
             item.toMetadata() + title + location ile yazılır
        g) geçici dosya silinir
   4) since = next_cursor, 1'e dön
   │
   ▼
cursor bellekte güncellenir (kalıcı değildir, bkz. KURULUM.md § 6.1)

GET /sync/status → { running, totalItems, processed, upserted, deleted,
                      skipped, failed, lastError, cursor }
```

> **Neden önce sil, sonra ekle?** External API `event_type: "upsert"` bir belgenin **yeni içerikle güncellendiğini** de ifade edebilir (aynı `id`, farklı `cdn_url`/`updated_at`). Eski chunk sayısı ile yeni chunk sayısı farklı olabileceğinden, güvenli ve idempotent bir upsert için önce `documentId`'ye ait tüm eski chunk'lar silinip ardından güncel içerik yeniden yazılır. Bu, External API kılavuzunun *"Idempotent işle (id bazlı upsert)"* notuyla uyumludur.

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

### 4.3 Konum Bulma — Sorgu (`POST /locate`)

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

İki hat **aynı `/sync/run` çalıştırmasında birlikte** beslenir (bkz. Bölüm 4.1) — External API'den gelen her belge, tek bir senkronizasyon geçişinde hem genel RAG hem de locate koleksiyonuna yazılır; ayrı ayrı tetiklemeye gerek yoktur.

---

## 6. Dış Bağımlılıklar

| Servis | Neden gerekli | Uygulama olmadan ne olur? |
|---|---|---|
| **External API** (`www2.mersin.edu.tr`) | Belgelerin **tek kaynağı** — hangi belgelerin yüklenip/silineceği buradan öğrenilir, dosya içeriği `cdn_url`'den indirilir | `/sync/run` başarısız olur (401/bağlantı hatası); mevcut Chroma verisi etkilenmez ama yeni/güncel belge işlenmez |
| **Ollama** (`localhost:11434`) | Embedding üretimi (`nomic-embed-text`) ve LLM cevap üretimi (`qwen2.5`) | `/rag/ask`, `/rag/add`, `/locate`, `/sync/run`, `/embed`, `/benchmark/run` çalışmaz (bağlantı hatası) |
| **ChromaDB** (`localhost:8000`, Docker) | Vektör depolama ve benzerlik araması | `/rag/*`, `/locate/*` ve `/sync/run` uçları `404`/bağlantı hatası verir |
| **Tesseract OCR** (native) | Görsel dosyalardan (jpg/png) metin çıkarımı | `/ocr/extract` yalnızca PDF için çalışır, görsellerde hata döner |
| **H2** (gömülü) | JPA altyapısı hazır bulundurulur | Şu an aktif bir entity kullanılmıyor; ek kurulum gerekmez |

---

## 7. Teknoloji Yığını

| Katman | Teknoloji |
|---|---|
| Dil / Platform | Java 21, Spring Boot 4.0.6 |
| Web | Spring MVC (`spring-boot-starter-webmvc`), embedded Tomcat |
| AI entegrasyonu | Spring AI 2.0.0-M5 (`spring-ai-starter-model-ollama`), doğrudan Ollama REST API (`WebClient`) |
| Belge kaynağı | Kurum içi External API (REST, `X-API-Key`), `WebClient` ile cursor tabanlı senkronizasyon + akış (streaming) dosya indirme |
| Belge ayrıştırma | Apache Tika 3.1.0 (genel RAG), Apache PDFBox 3.0.4 (PDF sayfa bazlı + OCR öncesi), Apache POI 5.4.0 (Word/Excel/PowerPoint) |
| OCR | Tess4j 5.11.0 (Tesseract JNA sarmalayıcısı) |
| Vektör DB | ChromaDB (Docker, REST API v2) |
| Veritabanı | H2 (in-memory) |
| Build | Maven (`mvnw`) |
| Yardımcı | Lombok |

---

## 8. Bilinen Sınırlamalar

- **H2 bellek-içi**: Uygulama yeniden başlatıldığında ilişkisel veriler (şu an aktif kullanılmasa da) sıfırlanır; Chroma verileri Docker volume'da kalıcıdır.
- **Senkronizasyon cursor'ı bellekte**: `ExternalDocumentSyncService` cursor'ı (`next_cursor`) kalıcı depoda tutmaz; uygulama yeniden başladığında `external-api.initial-since`'ten itibaren yeniden tarar. `documentId` bazlı sil-sonra-ekle deseni sayesinde bu güvenlidir (veri bozulmaz) ama gereksiz yeniden işleme anlamına gelir.
- **CPU-only çıkarım**: Uygun bir GPU (NVIDIA/AMD) yoksa Ollama modelleri CPU üzerinde çalışır; External API'den çok sayıda belge geldiğinde `/sync/run` **saatler** sürebilir.
- **Tek eşzamanlı senkronizasyon**: `ExternalDocumentSyncService` aynı anda yalnızca bir senkronizasyon çalıştırır; devam eden bir işlem varken yeni istek "Zaten devam eden bir senkronizasyon var." döner. Çalışan bir senkronizasyonu **durdurmak için API yoktur** — yalnızca uygulamayı yeniden başlatmak (JVM'i sonlandırmak) işlemi keser.
- **`/sync/run` otomatik değil**: Periyodik `@Scheduled` polling bilinçli olarak eklenmedi; senkronizasyon yalnızca `POST /sync/run` ile manuel tetiklenir (örn. dışarıdan bir cron/orkestrasyon aracıyla).
- **Sabit koleksiyon ID'leri**: Chroma koleksiyon ID'leri config dosyalarında sabittir; ChromaDB'yi sıfırdan kurduğunuzda bu ID'lerin güncellenmesi gerekir (bkz. KURULUM.md § 3.2).
