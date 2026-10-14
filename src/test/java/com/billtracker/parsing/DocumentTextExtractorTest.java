package com.billtracker.parsing;

import com.billtracker.parsing.DocumentTextExtractor.Kind;
import com.billtracker.parsing.DocumentTextExtractor.UnsupportedDocumentException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extractsTextFromPdf() throws IOException {
        byte[] pdf = pdf("Duke Energy", "Amount Due: $77.10", "Due Date: 11/02/2026");

        assertThat(extractor.detect("statement", "application/octet-stream", pdf)).isEqualTo(Kind.PDF);
        String text = extractor.extractText(Kind.PDF, pdf);
        assertThat(text).contains("Duke Energy").contains("Amount Due: $77.10");
    }

    @Test
    void rejectsPdfWithoutTextLayer() throws IOException {
        byte[] scanned = pdf();
        assertThatThrownBy(() -> extractor.extractText(Kind.PDF, scanned))
                .isInstanceOf(UnsupportedDocumentException.class)
                .hasMessageContaining("no text layer");
    }

    @Test
    void detectsByExtensionAndContentType() {
        byte[] any = "x".getBytes(StandardCharsets.UTF_8);
        assertThat(extractor.detect("bill.eml", null, any)).isEqualTo(Kind.EMAIL);
        assertThat(extractor.detect("x", "message/rfc822", any)).isEqualTo(Kind.EMAIL);
        assertThat(extractor.detect("bill.html", null, any)).isEqualTo(Kind.HTML);
        assertThat(extractor.detect("bill.txt", null, any)).isEqualTo(Kind.TEXT);
        assertThat(extractor.detect("photo.jpg", "image/jpeg", any)).isEqualTo(Kind.UNSUPPORTED);
    }

    @Test
    void htmlToTextKeepsTableRowsOnSeparateLines() {
        String text = DocumentTextExtractor.htmlToText(
                "<style>p{}</style><table><tr><td>Amount Due</td><td>&#36;5.00</td></tr><tr><td>Next</td></tr></table>");
        assertThat(text).contains("Amount Due $5.00\n").doesNotContain("p{}").doesNotContain("<");
    }

    static byte[] pdf(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (lines.length > 0) {
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.setLeading(16);
                    cs.newLineAtOffset(72, 720);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
