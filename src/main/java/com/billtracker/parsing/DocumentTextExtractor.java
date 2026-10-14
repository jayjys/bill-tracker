package com.billtracker.parsing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Turns an uploaded or attached document into plain text for {@link BillParser}. */
@Component
public class DocumentTextExtractor {

    public enum Kind { PDF, TEXT, HTML, EMAIL, UNSUPPORTED }

    public Kind detect(String filename, String contentType, byte[] bytes) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (isPdf(bytes) || type.contains("pdf") || name.endsWith(".pdf")) {
            return Kind.PDF;
        }
        if (type.contains("rfc822") || name.endsWith(".eml")) {
            return Kind.EMAIL;
        }
        if (type.contains("html") || name.endsWith(".html") || name.endsWith(".htm")) {
            return Kind.HTML;
        }
        if (type.startsWith("text/") || name.endsWith(".txt")) {
            return Kind.TEXT;
        }
        return Kind.UNSUPPORTED;
    }

    public String extractText(Kind kind, byte[] bytes) {
        return switch (kind) {
            case PDF -> pdfText(bytes);
            case TEXT -> new String(bytes, StandardCharsets.UTF_8);
            case HTML -> htmlToText(new String(bytes, StandardCharsets.UTF_8));
            case EMAIL, UNSUPPORTED -> throw new UnsupportedDocumentException(
                    "Can't read text directly from a " + kind.name().toLowerCase(Locale.ROOT) + " document");
        };
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    private static String pdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Keep text on the same visual line together so "Amount Due ... $84.12" stays on one line.
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text.isBlank()) {
                throw new UnsupportedDocumentException(
                        "This PDF has no text layer (likely a scan). Enter the bill details manually.");
            }
            return text;
        } catch (IOException e) {
            throw new UnsupportedDocumentException("Couldn't read PDF: " + e.getMessage());
        }
    }

    /** Good enough for bill emails: drop scripts/styles, turn block tags into newlines, strip the rest. */
    public static String htmlToText(String html) {
        return html
                .replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6]|table)>", "\n")
                .replaceAll("(?i)</t[dh]>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#36;", "$")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n\\s*\\n+", "\n");
    }

    public static class UnsupportedDocumentException extends RuntimeException {
        public UnsupportedDocumentException(String message) {
            super(message);
        }
    }
}
