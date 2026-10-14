package com.billtracker.parsing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders a plain-text statement as a bill-like PDF: each line's label at the left
 * margin and its value right-aligned in a second column, {@code linesPerPage} per page.
 * Also has a main() that writes the samples/ folder.
 */
public final class StatementPdf {

    private static final float LEFT = 60;
    private static final float RIGHT = 552;

    private StatementPdf() {
    }

    public static byte[] render(String text, int linesPerPage) throws IOException {
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] lines = text.split("\n");
            for (int start = 0; start < lines.length; start += linesPerPage) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = 740;
                    for (int i = start; i < Math.min(lines.length, start + linesPerPage); i++) {
                        String line = lines[i].strip();
                        // Split "Label      $12.34" into two columns at the last run of 2+ spaces.
                        int split = line.lastIndexOf("  ");
                        String label = split < 0 ? line : line.substring(0, split).strip();
                        String value = split < 0 ? "" : line.substring(split).strip();
                        PDType1Font font = label.startsWith("Total") || label.startsWith("Details") ? bold : regular;
                        write(cs, font, LEFT, y, label);
                        if (!value.isEmpty()) {
                            write(cs, font, RIGHT - font.getStringWidth(value) / 1000 * 10, y, value);
                        }
                        y -= 16;
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void write(PDPageContentStream cs, PDType1Font font, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(font, 10);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    /** Writes sample PDFs for trying the upload page: {@code samples/*.pdf}. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "samples");
        Files.createDirectories(dir);
        for (String name : new String[] {"pge-combined", "pge-electric", "coned-past-due", "socalgas"}) {
            String text = new String(StatementPdf.class.getResourceAsStream("/bills/" + name + ".txt").readAllBytes());
            Files.write(dir.resolve(name + ".pdf"), render(text, 40));
        }
    }
}
