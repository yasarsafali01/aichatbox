# External API

Kurum içi tüketicilere açık, sürümlü HTTP API. Tüm rotalar `/api/external/v1`
öneki altındadır ve `X-API-Key` header'ı ile korunur.

Base URL: `https://www2.mersin.edu.tr/api/external/v1`

---

## Kimlik Doğrulama

Her istek `X-API-Key` header'ı taşımalıdır.

```
X-API-Key: <api-key>
```

| Durum                 | HTTP | Yanıt                                         |
| --------------------- | ---- | --------------------------------------------- |
| Header eksik          | 401  | `{"error": "Missing X-API-Key header"}`       |
| Header yanlış         | 401  | `{"error": "Invalid API key"}`                |
| API yapılandırılmamış | 503  | `{"error": "External API is not configured"}` |

---

## 1. GET /documents/changes

Doküman kayıtlarındaki değişiklikleri (yükleme/güncelleme/silme) `updated_at`
artan sırayla döndürür. Aynı `updated_at` değerine sahip kayıtlar `id` artan
sırayla (deterministic tie-break) sıralanır. `since` zamanından sonra
güncellenen kayıtları verir.

```
GET /documents/changes?since=<RFC3339>&limit=<int>
```

### Query Parametreleri

| Param   | Zorunlu | Varsayılan | Açıklama                                                                           |
| ------- | ------- | ---------- | ---------------------------------------------------------------------------------- |
| `since` | Evet    | —          | RFC3339 zaman damgası; bu andan sonra güncellenenler döner                         |
| `limit` | Hayır   | `100`      | Maks öğe sayısı. Pozitif tam sayı olmalı; `100`'ü aşan değerler `100`'e sınırlanır |

### Yanıt Gövdesi

| Alan          | Tip                    | Açıklama                                                                                                                                                                                                  |
| ------------- | ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `items`       | array                  | Değişen dokümanlar (aşağıdaki nesne)                                                                                                                                                                      |
| `next_cursor` | string (RFC3339, nano) | Bir sonraki istekte `since` olarak kullanılacak değer. Saniye-altı (nano) hassasiyet taşır. Son öğenin `updated_at`'idir; `items` boşsa istekte gönderilen `since` (UTC'ye normalize edilmiş) aynen döner |

Her `items` öğesi:

| Alan            | Tip                       | Açıklama                                                                                        |
| --------------- | ------------------------- | ----------------------------------------------------------------------------------------------- |
| `id`            | string                    | Doküman UUID'si                                                                                 |
| `event_type`    | `"upsert"` \| `"deleted"` | Yüklendi/güncellendi veya silindi                                                               |
| `original_name` | string                    | Orijinal dosya adı                                                                              |
| `file_type`     | string                    | Doküman tür etiketi (örn. `"PDF"`)                                                              |
| `mime_type`     | string                    | MIME türü                                                                                       |
| `file_size`     | int                       | Bayt                                                                                            |
| `cdn_url`       | string \| null            | İndirme URL'i; `deleted` ise `null`                                                             |
| `birim_id`      | int                       | Ait olduğu birim. İŞARETLİ: negatif = harici birim (gerçek id = -birim_id)                      |
| `birim_name`    | object `{ "tr","en" }`    | Birim adı — **ham çok dilli map**; dile çözüm tüketicidedir                                     |
| `updated_at`    | string (RFC3339, nano)    | Son güncelleme zamanı; saniye-altı (nano) kesir içerebilir (örn. `2024-03-10T08:15:30.482913Z`) |

> **`birim_name` ham `{tr,en}` map olarak döner**; sunucu tarafında bir dile
> çözülmez. Tüketici (örn. Pano AI araması) istenen dile kendisi çözer. Bu alan
> doküman metadatasını zenginleştirmek için eklenmiştir (bkz.
> `PANO_AI_SEARCH_API_CONTRACT.md`).

### İstek

```bash
curl -s \
  -H "X-API-Key: <api-key>" \
  "https://www2.mersin.edu.tr/api/external/v1/documents/changes?since=2024-01-01T00:00:00Z&limit=50"
```

### Yanıt

```json
{
  "items": [
    {
      "id": "5e4f5f2a-2b7a-4a41-9c9a-1a2b3c4d5e6f",
      "event_type": "upsert",
      "original_name": "2024-yonetmelik.pdf",
      "file_type": "PDF",
      "mime_type": "application/pdf",
      "file_size": 245678,
      "cdn_url": "https://cdn.mersin.edu.tr/dokumanlar/5e4f5f2a.pdf",
      "birim_id": 5,
      "birim_name": { "tr": "Öğrenci İşleri Daire Başkanlığı", "en": "Registrar's Office" },
      "updated_at": "2024-03-10T08:15:30.482913Z"
    },
    {
      "id": "8b1c2d3e-4f5a-4b6c-8d9e-0f1a2b3c4d5e",
      "event_type": "deleted",
      "original_name": "eski-form.docx",
      "file_type": "DOCX",
      "mime_type": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "file_size": 30720,
      "cdn_url": null,
      "birim_id": 5,
      "birim_name": { "tr": "Öğrenci İşleri Daire Başkanlığı", "en": "Registrar's Office" },
      "updated_at": "2024-03-10T09:02:11.20457Z"
    }
  ],
  "next_cursor": "2024-03-10T09:02:11.20457Z"
}
```

### Hatalar

| Durum                          | HTTP | Yanıt                                                                    |
| ------------------------------ | ---- | ------------------------------------------------------------------------ |
| `since` eksik                  | 400  | `{"error": "query parameter 'since' is required (RFC3339 format)"}`      |
| `since` geçersiz format        | 400  | `{"error": "query parameter 'since' must be a valid RFC3339 timestamp"}` |
| `limit` pozitif tam sayı değil | 400  | `{"error": "query parameter 'limit' must be a positive integer"}`        |
| Sunucu hatası                  | 500  | `{"error": "Internal server error"}`                                     |

### Senkronizasyon (Polling + Cursor)

Bu uç nokta cursor tabanlı çalışır. İlk isteği eski bir `since` ile başlat, her
yanıttaki `next_cursor`'ı sonraki isteğin `since`'i yap. `items` boş dönene
kadar döngüyü sürdür; ardından aynı akışı periyodik tekrarla. `limit`'i aşan
büyük kayıt kümeleri bu döngüyle birden çok sayfada eksiksiz teslim edilir.

`items` boş döndüğünde `next_cursor`, gönderdiğin `since` ile aynı kalır (yeni
kayıt yok demektir); bir sonraki poll'de bu değeri kullanmaya devam et.

```
since = <eski bir tarih>
loop:
  resp = GET /documents/changes?since={since}&limit=100
  process(resp.items)
  since = resp.next_cursor
  sleep(5 dakika)
```

### Notlar

- **Idempotent işle (id bazlı upsert).** `next_cursor` tam hassasiyetlidir
  (RFC3339 nano) ve sonuçlar `updated_at`, ardından `id`'ye göre deterministik
  sıralanır; yine de nadir sınır durumlarında aynı doküman ardışık poll'lerde
  tekrar gelebilir. Kayıtları `id` üzerinden upsert et, sayma/append yapma.
- **Timestamp kaynağı (bilinen sınırlama).** `updated_at`, PostgreSQL'de
  mikrosaniye hassasiyetli (`TIMESTAMPTZ`) tutulur ve her
  yükleme/güncelleme/silmede sunucu saatiyle (`NOW()`) ilerletilir. Cursor
  yalnızca `updated_at` taşıdığı ve sorgu kesin `>` kullandığı için, tek bir
  işlemde (transaction) `limit`'ten fazla doküman **birebir aynı** `updated_at`
  ile yazılırsa sayfa sınırına denk gelen fazlalık satırlar bir sonraki sayfada
  atlanabilir. Normal kullanımda dokümanlar teker teker yazıldığından bu sorun oluşmaz.

---

## 2. GET /documents/:id

Verilen UUID'ye sahip tek bir doküman kaydını döndürür. Kayıt aktif veya
silinmiş olabilir; her iki durumda da döner.

```
GET /documents/:id
```

### Path Parametreleri

| Param | Zorunlu | Açıklama        |
| ----- | ------- | --------------- |
| `id`  | Evet    | Doküman UUID'si |

### Yanıt Gövdesi

Tek bir doküman nesnesi döner (`/documents/changes` `items` öğesiyle aynı
alanlar; `next_cursor` yoktur).

| Alan            | Tip                       | Açıklama                                                   |
| --------------- | ------------------------- | ---------------------------------------------------------- |
| `id`            | string                    | Doküman UUID'si                                            |
| `event_type`    | `"upsert"` \| `"deleted"` | Son olay tipi                                              |
| `original_name` | string                    | Orijinal dosya adı                                         |
| `file_type`     | string                    | Doküman tür etiketi (örn. `"PDF"`)                         |
| `mime_type`     | string                    | MIME türü                                                  |
| `file_size`     | int                       | Bayt                                                       |
| `cdn_url`       | string \| null            | İndirme URL'i; `deleted` ise `null`                        |
| `birim_id`      | int                       | Ait olduğu birim. Negatif = harici birim (id = -birim_id)  |
| `birim_name`    | object `{ "tr","en" }`    | Birim adı — ham çok dilli map                              |
| `updated_at`    | string (RFC3339, nano)    | Son güncelleme zamanı; saniye-altı (nano) kesir içerebilir |

### İstek

```bash
curl -s \
  -H "X-API-Key: <api-key>" \
  "https://www2.mersin.edu.tr/api/external/v1/documents/5e4f5f2a-2b7a-4a41-9c9a-1a2b3c4d5e6f"
```

### Yanıt

```json
{
  "id": "5e4f5f2a-2b7a-4a41-9c9a-1a2b3c4d5e6f",
  "event_type": "upsert",
  "original_name": "2024-yonetmelik.pdf",
  "file_type": "PDF",
  "mime_type": "application/pdf",
  "file_size": 245678,
  "cdn_url": "https://cdn.mersin.edu.tr/dokumanlar/5e4f5f2a.pdf",
  "birim_id": 5,
  "birim_name": { "tr": "Öğrenci İşleri Daire Başkanlığı", "en": "Registrar's Office" },
  "updated_at": "2024-03-10T08:15:30.482913Z"
}
```

### Hatalar

| Durum                   | HTTP | Yanıt                                     |
| ----------------------- | ---- | ----------------------------------------- |
| `id` geçerli UUID değil | 400  | `{"error": "':id' must be a valid UUID"}` |
| Doküman bulunamadı      | 404  | `{"error": "not found"}`                  |
| Sunucu hatası           | 500  | `{"error": "Internal server error"}`      |
