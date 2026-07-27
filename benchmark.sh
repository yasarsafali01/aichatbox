#!/usr/bin/env bash
set -euo pipefail

MODELS=("qwen3:32b" "deepseek-v3" "llama3.3:70b")
QUESTIONS_FILE="benchmark_questions.json"
RESULTS_DIR="benchmark_results"
APP_YML="src/main/resources/application.yml"
PORT=8080
BASE_URL="http://localhost:${PORT}"
APP_PID=""

mkdir -p "$RESULTS_DIR"

if [ ! -f "$QUESTIONS_FILE" ]; then
  cat > "$QUESTIONS_FILE" <<'EOF'
[
  "Spring Boot nedir?",
  "RAG mimarisi nasıl çalışır?",
  "ChromaDB ne için kullanılır?"
]
EOF
fi

set_model() {
  local model="$1"
  sed -i "s/^\(\s*model:\s*\).*# kolayca değiştirilebilsin/\1${model} # kolayca değiştirilebilsin/" "$APP_YML"
}

wait_for_app() {
  echo "Uygulamanın ayağa kalkması bekleniyor..."
  for _ in $(seq 1 60); do
    if curl -sf "${BASE_URL}/" > /dev/null 2>&1; then
      echo "Uygulama hazır."
      return 0
    fi
    sleep 2
  done
  echo "Uygulama ${PORT} portunda başlatılamadı." >&2
  return 1
}

stop_app() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    echo "Uygulama durduruluyor (PID: $APP_PID)..."
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  APP_PID=""
}

trap stop_app EXIT

for MODEL in "${MODELS[@]}"; do
  echo "===================================="
  echo "Model: $MODEL"
  echo "===================================="

  set_model "$MODEL"

  SAFE_NAME="${MODEL//:/_}"

  echo "Spring Boot başlatılıyor..."
  ./mvnw -q spring-boot:run > "${RESULTS_DIR}/${SAFE_NAME}_server.log" 2>&1 &
  APP_PID=$!

  if ! wait_for_app; then
    stop_app
    continue
  fi

  OUTPUT_FILE="${RESULTS_DIR}/${SAFE_NAME}.json"

  echo "Test soruları gönderiliyor..."
  curl -sf -X POST "${BASE_URL}/benchmark/run" \
    -H "Content-Type: application/json" \
    -d @"$QUESTIONS_FILE" \
    -o "$OUTPUT_FILE"

  echo "Sonuçlar kaydedildi: $OUTPUT_FILE"

  stop_app
  sleep 2
done

echo "Tüm modeller test edildi. Sonuçlar: ${RESULTS_DIR}/"
