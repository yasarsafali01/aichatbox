package com.yasarsafali.rag_backend.dto.locate;

public record LocateResult(String title, String fileName, String location, String url, String text, double distance) {
}
