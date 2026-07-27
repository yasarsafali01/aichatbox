#!/usr/bin/env python3
import json
import sys
from pathlib import Path
from statistics import mean

RESULTS_DIR = Path("benchmark_results")
MODEL_FILES = {
    "qwen3:32b": "qwen3_32b.json",
    "deepseek-v3": "deepseek-v3.json",
    "llama3.3:70b": "llama3.3_70b.json",
}


def load_results():
    loaded = {}
    for model, filename in MODEL_FILES.items():
        path = RESULTS_DIR / filename
        if not path.exists():
            print(f"Uyarı: {path} bulunamadı, atlanıyor.", file=sys.stderr)
            continue
        with path.open(encoding="utf-8") as f:
            loaded[model] = json.load(f)
    return loaded


def print_summary(results):
    print(f"{'Model':<15} {'Soru Sayısı':<12} {'Ort. Süre (ms)':<16} {'Min (ms)':<10} {'Max (ms)':<10}")
    print("-" * 65)
    for model, entries in results.items():
        times = [e["responseTimeMs"] for e in entries]
        print(f"{model:<15} {len(entries):<12} {mean(times):<16.1f} {min(times):<10} {max(times):<10}")


def print_side_by_side(results):
    models = list(results.keys())
    if not models:
        return
    question_count = len(results[models[0]])

    for i in range(question_count):
        question = results[models[0]][i]["question"]
        print("\n" + "=" * 80)
        print(f"Soru: {question}")
        print("=" * 80)
        for model in models:
            entry = results[model][i]
            print(f"\n[{model}] ({entry['responseTimeMs']} ms)")
            print(entry["answer"])


def main():
    results = load_results()
    if not results:
        print("Karşılaştırılacak sonuç bulunamadı. Önce benchmark.sh çalıştırın.", file=sys.stderr)
        sys.exit(1)

    print("\n### Performans Özeti ###\n")
    print_summary(results)

    print("\n\n### Yan Yana Karşılaştırma ###")
    print_side_by_side(results)


if __name__ == "__main__":
    main()
