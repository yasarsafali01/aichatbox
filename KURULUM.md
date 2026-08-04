# aichatbox — Linux Kurulum Rehberi

Bu doküman, **aichatbox** (Spring Boot 4 / Java 21 tabanlı RAG ve belge-içi arama uygulaması, Maven modül adı `rag-backend`) projesinin sıfırdan bir Linux makinesine kurulması için gereken tüm adımları içerir. Rehber Ubuntu/Debian tabanlı dağıtımlar esas alınarak hazırlanmıştır; farklı dağıtımlar için paket yöneticisi komutları uyarlanmalıdır.

---

## 1. Mimari Özeti

Uygulama aşağıdaki bileşenlerden oluşur:

| Bileşen | Rol | Varsayılan Port |
|---|---|---|
| Spring Boot uygulaması (aichatbox) | REST API, iş mantığı | `8080` |
| Ollama | Yerel LLM çıkarımı (chat: `qwen2.5`, embedding: `nomic-embed-text`) | `11434` |
| ChromaDB | Vektör veritabanı — **iki ayrı koleksiyon** kullanılır (genel RAG + konum bulma) | `8000` |
| H2 (in-memory) | İlişkisel veri (uygulama içinde gömülü) | — |
| Tesseract OCR | Görsel belgelerden metin çıkarımı | — |

Uygulamanın ne iş yaptığına ve iç işleyişine dair ayrıntılı anlatım için bkz. **[PROJE_MIMARISI.md](PROJE_MIMARISI.md)**.

> Not: H2 bellek-içi (in-memory) çalıştığı için ayrı bir kurulum gerektirmez, ancak **uygulama her yeniden başladığında veriler sıfırlanır**.

---

## 2. Ön Gereksinimler

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl wget git unzip apt-transport-https ca-certificates gnupg lsb-release
```

### 2.1 Java 21 (JDK)

```bash
sudo apt install -y openjdk-21-jdk
java -version   # openjdk version "21.x.x" görülmeli
```

### 2.2 Maven

Proje `mvnw` (Maven Wrapper) ile geldiği için ayrı bir Maven kurulumu **zorunlu değildir**. Yine de sistem genelinde kullanmak isterseniz:

```bash
sudo apt install -y maven
mvn -version
```

Wrapper kullanacaksanız çalıştırma izni verin:

```bash
chmod +x mvnw
```

> PDF/Word/Excel/PowerPoint ayrıştırma (Apache Tika, PDFBox, Apache POI) tamamen saf Java kütüphaneleridir; ek bir native paket gerektirmezler.

---

## 3. Docker Kurulumu (ChromaDB için)

```bash
# Eski sürümleri kaldır (varsa)
sudo apt remove -y docker docker-engine docker.io containerd runc

# Resmi Docker deposu
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Kullanıcıyı docker grubuna ekle (sudo'suz kullanım için)
sudo usermod -aG docker $USER
newgrp docker

sudo systemctl enable --now docker
docker --version
```

### 3.1 ChromaDB Konteynerini Başlatma

```bash
docker run -d \
  --name chromadb \
  --restart unless-stopped \
  -p 8000:8000 \
  -v chroma-data:/data \
  chromadb/chroma:latest
```

Doğrulama:

```bash
curl -s http://localhost:8000/api/v2/heartbeat
# {"nanosecond heartbeat": ...}
```

### 3.2 Koleksiyonları Oluşturma (ÖNEMLİ)

aichatbox iki farklı amaç için **iki ayrı Chroma koleksiyonu** kullanır (bkz. PROJE_MIMARISI.md § 4):

1. **Genel RAG koleksiyonu** — `application.yml` → `chroma.collection`
2. **Konum bulma (locate) koleksiyonu** — `application.properties` → `locate.chroma.collection`

Taze bir ChromaDB üzerinde bu koleksiyonlar otomatik oluşturulmaz — uygulama var olmayan bir koleksiyona veri eklemeye/sorgulamaya çalışırsa `404 Collection ... does not exist` hatası alır.

Her iki koleksiyonu da manuel oluşturun:

```bash
# 1) Genel RAG koleksiyonu
curl -s -X POST "http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections" \
  -H "Content-Type: application/json" \
  -d '{"name":"rag_documents"}'

# 2) Konum bulma koleksiyonu
curl -s -X POST "http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections" \
  -H "Content-Type: application/json" \
  -d '{"name":"locate_documents"}'
```

Dönen yanıtlardaki `"id"` alanlarını kopyalayıp ilgili yapılandırma dosyalarına yazın:

```yaml
# src/main/resources/application.yml
chroma:
  base-url: http://localhost:8000
  tenant-id: default_tenant
  database: default_database
  collection: <GENEL_RAG_KOLEKSIYON_ID>
  distance-threshold: 220.0
```

```properties
# src/main/resources/application.properties
locate.chroma.collection=<LOCATE_KOLEKSIYON_ID>
```

> **Not:** `chroma.base-url` varsayılan olarak `http://[::1]:8000` (IPv6 loopback) tanımlıdır. Sisteminizde IPv6 loopback etkin değilse veya sorun yaşarsanız `http://localhost:8000` ya da `http://127.0.0.1:8000` kullanın.

---

## 4. Ollama Kurulumu (LLM ve Embedding Modelleri)

```bash
curl -fsSL https://ollama.com/install.sh | sh
sudo systemctl enable --now ollama

# Servisin ayakta olduğunu doğrula
curl -s http://localhost:11434/api/tags
```

Gerekli modelleri indirin:

```bash
ollama pull qwen2.5
ollama pull nomic-embed-text
```

> **Donanım notu:** Ollama, NVIDIA (CUDA) veya AMD (ROCm) GPU'ları destekler. Uyumlu bir GPU yoksa modeller tamamen **CPU üzerinde** çalışır; bu durumda `qwen2.5` gibi büyük modellerde yanıt süreleri belirgin şekilde artar. GPU hızlandırma için ilgili sürücülerin (NVIDIA driver + CUDA toolkit) önceden kurulu olması gerekir.

---

## 5. Tesseract OCR Kurulumu

Görsel (jpg/png) belgelerden metin çıkarımı için Tesseract gereklidir (`/ocr/extract`). Proje varsayılan dili **Türkçe** (`ocr.language=tur`) olarak ayarlıdır. PDF dosyaları OCR yerine doğrudan PDFBox ile metne çevrilir.

```bash
sudo apt install -y tesseract-ocr tesseract-ocr-tur libtesseract-dev
tesseract --version
```

`tessdata` klasörünün yolunu bulun ve `application.properties` içine yazın:

```bash
dpkg -L tesseract-ocr-tur | grep tessdata
# örn: /usr/share/tesseract-ocr/5/tessdata/tur.traineddata
```

```properties
# src/main/resources/application.properties
ocr.tessdata-path=/usr/share/tesseract-ocr/5/tessdata
ocr.language=tur
```

---

## 6. Projeyi Alma ve Yapılandırma

```bash
git clone <REPO_URL> aichatbox
cd aichatbox
```

`src/main/resources/application.properties` ve `application.yml` dosyalarında aşağıdaki noktaları ortamınıza göre kontrol edin:

| Dosya | Anahtar | Açıklama |
|---|---|---|
| `application.properties` | `ai.ollama.base-url` | Ollama servis adresi |
| `application.properties` | `ocr.tessdata-path` | Tesseract dil dosyaları yolu |
| `application.properties` | `security.api-key.chroma` / `security.api-key.admin` | `/rag`, `/locate` ve diğer uçlar için API anahtarları (bkz. Bölüm 9) |
| `application.properties` | `locate.chroma.collection` | Konum bulma koleksiyon ID'si |
| `application.properties` | `locate.pdf.base-url` | İndekslenen dosyalara tıklanabilir bağlantı üretmek için taban yol (`file:///...`) |
| `application.yml` | `spring.ai.ollama.base-url` | Ollama servis adresi (chat/embedding) |
| `application.yml` | `chroma.base-url` | ChromaDB adresi |
| `application.yml` | `chroma.collection` | Genel RAG koleksiyon ID'si (Bölüm 3.2) |

> **Güvenlik uyarısı:** `security.api-key.*` ve koleksiyon ID'leri repo içinde düz metin olarak tutulmaktadır. Üretim ortamına taşırken bu değerleri ortam değişkenleri (`SECURITY_API_KEY_CHROMA` vb.) veya bir secret manager üzerinden vermeniz önerilir.

---

## 7. Derleme ve Çalıştırma

### 7.1 Geliştirme modu (doğrudan çalıştırma)

```bash
./mvnw spring-boot:run
```

### 7.2 Üretim (jar) modu

```bash
./mvnw clean package -DskipTests
java -jar target/rag-backend-0.0.1-SNAPSHOT.jar
```

### 7.3 Doğrulama

```bash
curl http://localhost:8080/
# Welcome to the RAG Backend!
```

`/rag` ve `/locate` altındaki uçlar API anahtarı ister (bkz. Bölüm 9):

```bash
curl -X POST http://localhost:8080/rag/ask \
  -H "X-API-Key: <security.api-key.chroma değeri>" \
  -H "Content-Type: text/plain" \
  -d "Örnek soru?"
```

---

## 8. Kalıcı Servis Olarak Çalıştırma (systemd)

```ini
# /etc/systemd/system/aichatbox.service
[Unit]
Description=aichatbox (Spring Boot RAG Backend)
After=network.target docker.service ollama.service

[Service]
Type=simple
User=<KULLANICI_ADI>
WorkingDirectory=/opt/aichatbox
ExecStart=/usr/bin/java -jar /opt/aichatbox/target/rag-backend-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now aichatbox
sudo systemctl status aichatbox
```

---

## 9. Güvenlik: API Anahtarı Filtresi

`ApiKeyFilter`, OPTIONS dışındaki **tüm** istekleri denetler ve `X-API-Key` header'ı bekler:

| İstek yolu | Gereken anahtar (property) |
|---|---|
| `/rag/**`, `/locate/**` | `security.api-key.chroma` |
| Diğer tüm yollar (`/`, `/embed`, `/ocr/**`, `/benchmark/**`) | `security.api-key.admin` |

Anahtar eksik/yanlışsa `401 Unauthorized` + `{"error":"Geçersiz veya eksik X-API-Key"}` döner.

---

## 10. Uç Nokta (Endpoint) Referansı

| Metod | Yol | Anahtar grubu | Açıklama |
|---|---|---|---|
| `GET` | `/` | admin | Sağlık kontrolü |
| `GET` | `/embed?text=...` | admin | Metni embedding vektörüne çevirir |
| `POST` | `/rag/add` | chroma | Genel RAG koleksiyonuna tek metin ekler |
| `POST` | `/rag/ask` | chroma | RAG tabanlı soru-cevap (LLM yanıtı üretir) |
| `POST` | `/rag/load-pdf?path=...` | chroma | Tek dosyayı işleyip genel RAG koleksiyonuna yükler |
| `POST` | `/rag/load-folder?path=...` | chroma | Klasördeki desteklenen dosyaları arka planda toplu yükler |
| `GET` | `/rag/load-status` | chroma | Toplu yükleme ilerleme durumu |
| `POST` | `/locate` | chroma | Soruya en yakın belge/sayfa konumlarını döner (LLM çağırmaz) |
| `POST` | `/locate/index-pdf?path=...` | chroma | Tek dosyayı sayfa/paragraf/slayt bazlı indeksler |
| `POST` | `/locate/index-folder?path=...` | chroma | Klasördeki desteklenen dosyaları arka planda toplu indeksler |
| `GET` | `/locate/index-status` | chroma | Toplu indeksleme ilerleme durumu |
| `POST` | `/ocr/extract` (multipart) | admin | Görsel/PDF'den OCR ile metin çıkarır |
| `POST` | `/benchmark/run` | admin | Model karşılaştırma testleri çalıştırır |

Desteklenen belge türleri (`/rag/*` ve `/locate/*`): `pdf, doc, docx, xls, xlsx, ppt, pptx`

> **Encoding notu:** `path` parametresinde Türkçe karakter (ş, ğ, ı, ö, ü, ç) geçiyorsa, isteği gönderirken URL'nin **UTF-8** olarak yüzde-kodlanmış (percent-encoded) olduğundan emin olun. Aksi halde Tomcat `Character decoding failed` hatası verir.

---

## 11. Sorun Giderme

| Belirti | Olası Neden | Çözüm |
|---|---|---|
| `Connection refused ... :8000` | ChromaDB çalışmıyor | `docker ps` ile kontrol edin, gerekiyorsa `docker start chromadb` |
| `404 Collection ... does not exist` | `chroma.collection` / `locate.chroma.collection` geçersiz/eski ID | Bölüm 3.2'yi tekrarlayıp yeni ID'yi ilgili dosyaya yapıştırın |
| `401 Unauthorized` | `X-API-Key` header eksik/yanlış | İlgili uç için doğru anahtarı (chroma/admin) gönderin |
| `Port 8080 was already in use` | Önceki uygulama örneği hâlâ ayakta | `lsof -i:8080` ile PID bulup sonlandırın |
| Embedding/chat istekleri çok yavaş | GPU yok, CPU üzerinde çıkarım | Beklenen davranış; uyumlu GPU + sürücü ekleyin veya daha küçük model kullanın |
| OCR sonucu boş/hatalı | `tessdata-path` yanlış veya dil paketi eksik | Bölüm 5'i kontrol edin |
| Yeniden başlatınca veriler kayboldu | H2 in-memory kullanılıyor | Kalıcı veri için `spring.datasource.url` kalıcı bir dosya/DB'ye yönlendirilmeli |

---

## 12. Hızlı Başlangıç Özeti

```bash
# 1. Bağımlılıklar
sudo apt update && sudo apt install -y openjdk-21-jdk docker.io tesseract-ocr tesseract-ocr-tur

# 2. Ollama
curl -fsSL https://ollama.com/install.sh | sh
ollama pull qwen2.5 && ollama pull nomic-embed-text

# 3. ChromaDB
docker run -d --name chromadb --restart unless-stopped -p 8000:8000 -v chroma-data:/data chromadb/chroma:latest

# 4. İki koleksiyonu oluştur ve ID'leri application.yml / application.properties'e yapıştır (Bölüm 3.2)

# 5. Uygulamayı başlat
./mvnw spring-boot:run
```
