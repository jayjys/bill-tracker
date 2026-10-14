package com.billtracker.parsing;

import com.billtracker.TestFixtures;
import com.billtracker.bill.UtilityType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real bills are laid out in columns: labels on the left, amounts right-aligned far
 * away, sometimes across pages. This renders statements that way and runs the full
 * PDF → text → parse path.
 */
class RealisticPdfTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();
    private final BillParser parser = new BillParser();

    @Test
    void parsesATwoColumnMultiPageCombinedStatement() throws IOException {
        byte[] pdf = StatementPdf.render(TestFixtures.text("pge-combined.txt"), 22);

        String text = extractor.extractText(extractor.detect("statement.pdf", "application/pdf", pdf), pdf);
        List<ParsedBill> bills = parser.parse(text);

        assertThat(bills).hasSize(2);
        assertThat(bills).extracting(ParsedBill::utilityType).containsExactly(UtilityType.ELECTRIC, UtilityType.GAS);
        assertThat(bills.get(0).amount()).isEqualByComparingTo("71.06");
        assertThat(bills.get(1).amount()).isEqualByComparingTo("112.53");
        assertThat(bills).allSatisfy(b -> assertThat(b.warnings()).isEmpty());
    }

    @Test
    void parsesEachSampleFormatFromPdf() throws IOException {
        for (String fixture : List.of("pge-electric.txt", "coned-past-due.txt", "socalgas.txt")) {
            String original = TestFixtures.text(fixture);
            byte[] pdf = StatementPdf.render(original, 60);
            String text = extractor.extractText(DocumentTextExtractor.Kind.PDF, pdf);

            ParsedBill fromPdf = parser.parse(text).getFirst();
            ParsedBill fromText = parser.parse(original).getFirst();
            assertThat(fromPdf.amount()).as(fixture).isEqualByComparingTo(fromText.amount());
            assertThat(fromPdf.periodEnd()).as(fixture).isEqualTo(fromText.periodEnd());
            assertThat(fromPdf.usageQuantity()).as(fixture).isEqualByComparingTo(fromText.usageQuantity());
        }
    }
}
