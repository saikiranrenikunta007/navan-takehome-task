package com.navan.task.extraction;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Stubbed OCR. The fixtures are plain-text stand-ins for scanned receipts, so
 * "OCR" here is simply decoding the stored bytes as UTF-8. A real OCR/VLM
 * vendor would replace this one class; nothing else changes.
 *
 * This is the documented stub referenced in the README.
 */
@Component
public class OcrService {

    public String extractText(byte[] fileBytes) {
        return new String(fileBytes, StandardCharsets.UTF_8);
    }
}
