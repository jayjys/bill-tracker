package com.billtracker.parsing;

import com.billtracker.TestFixtures;
import com.billtracker.bill.ChargeCategory;
import com.billtracker.bill.UtilityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class BillParserTest {

    private final BillParser parser = new BillParser();

    @Test
    void parsesPgeElectricStatement() {
        ParsedBill bill = parser.parse(TestFixtures.text("pge-electric.txt")).getFirst();

        assertThat(bill.provider()).isEqualTo("PG&E");
        assertThat(bill.utilityType()).isEqualTo(UtilityType.ELECTRIC);
        assertThat(bill.amount()).isEqualByComparingTo("98.62");
        assertThat(bill.dueDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(bill.periodStart()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(bill.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 29));
        assertThat(bill.usageQuantity()).isEqualByComparingTo("412");
        assertThat(bill.usageUnit()).isEqualTo("kWh");
        assertThat(bill.accountLast4()).isEqualTo("8905");
        assertThat(bill.isComplete()).isTrue();
        assertThat(bill.confidence()).isEqualByComparingTo("1.000");
    }

    @Test
    void prefersCurrentChargesOverAmountDueWhenThereIsAPastDueBalance() {
        ParsedBill bill = parser.parse(TestFixtures.text("coned-past-due.txt")).getFirst();

        // Total due ($210.87) includes last month's unpaid $88.10; the cost of this period is $121.45.
        assertThat(bill.amount()).isEqualByComparingTo("121.45");
        assertThat(bill.provider()).isEqualTo("Con Edison");
        assertThat(bill.periodStart()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(bill.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(bill.dueDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(bill.usageQuantity()).isEqualByComparingTo("538");
        assertThat(bill.utilityType()).isEqualTo(UtilityType.ELECTRIC);
        assertThat(bill.accountLast4()).isEqualTo("0017");
    }

    @Test
    void parsesGasBillWithTwoDigitYearsAndPeriodSpanningNewYear() {
        ParsedBill bill = parser.parse(TestFixtures.text("socalgas.txt")).getFirst();

        assertThat(bill.provider()).isEqualTo("SoCalGas");
        assertThat(bill.utilityType()).isEqualTo(UtilityType.GAS);
        assertThat(bill.amount()).isEqualByComparingTo("89.68");
        assertThat(bill.dueDate()).isEqualTo(LocalDate.of(2026, 2, 2));
        assertThat(bill.periodStart()).isEqualTo(LocalDate.of(2025, 12, 9));
        assertThat(bill.periodEnd()).isEqualTo(LocalDate.of(2026, 1, 8));
        assertThat(bill.usageQuantity()).isEqualByComparingTo("54");
        assertThat(bill.usageUnit()).isEqualTo("therms");
    }

    @Test
    void parsesHtmlWaterBillAndFallsBackToHeadingForUnknownProvider() {
        String text = DocumentTextExtractor.htmlToText(TestFixtures.text("city-water.html"));
        ParsedBill bill = parser.parse(text).getFirst();

        assertThat(bill.provider()).isEqualTo("City of Springfield Water & Sewer");
        assertThat(bill.utilityType()).isEqualTo(UtilityType.WATER);
        assertThat(bill.amount()).isEqualByComparingTo("68.05");
        assertThat(bill.dueDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(bill.periodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(bill.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(bill.usageQuantity()).isEqualByComparingTo("4200");
        assertThat(bill.usageUnit()).isEqualTo("gallons");
    }

    @Test
    void flagsTextWithoutBillDataAsIncomplete() {
        ParsedBill bill = parser.parse(TestFixtures.text("unreadable.txt")).getFirst();

        assertThat(bill.amount()).isNull();
        assertThat(bill.isComplete()).isFalse();
        assertThat(bill.confidence()).isLessThan(new BigDecimal("0.5"));
    }

    @Test
    void usesProviderHintWhenTextHasNoKnownProvider() {
        ParsedBill bill = parser.parse("Your internet bill\nAmount due: $65.00\nDue date: 10/01/2026",
                "Sonic Internet").getFirst();

        assertThat(bill.provider()).isEqualTo("Sonic Internet");
        assertThat(bill.utilityType()).isEqualTo(UtilityType.INTERNET);
    }

    @Test
    void ignoresImplausibleDateRangesWhenLookingForThePeriod() {
        // A 2-year range is a contract term, not a billing period.
        ParsedBill bill = parser.parse("Contract 01/01/2025 - 01/01/2027\nAmount due $40.00").getFirst();
        assertThat(bill.periodStart()).isNull();
        assertThat(bill.periodEnd()).isNull();
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(parser.parse("").getFirst().isComplete()).isFalse();
        assertThat(parser.parse(null).getFirst().amount()).isNull();
    }

    @Test
    void splitsCombinedElectricAndGasStatementIntoTwoBills() {
        List<ParsedBill> bills = parser.parse(TestFixtures.text("pge-combined.txt"));

        assertThat(bills).hasSize(2);
        ParsedBill electric = bills.get(0);
        ParsedBill gas = bills.get(1);
        assertThat(electric.utilityType()).isEqualTo(UtilityType.ELECTRIC);
        assertThat(electric.amount()).isEqualByComparingTo("71.06");
        assertThat(electric.usageQuantity()).isEqualByComparingTo("388");
        assertThat(gas.utilityType()).isEqualTo(UtilityType.GAS);
        assertThat(gas.amount()).isEqualByComparingTo("112.53");
        assertThat(gas.usageUnit()).isEqualTo("therms");
        // Statement-level fields are shared.
        assertThat(bills).allSatisfy(b -> {
            assertThat(b.provider()).isEqualTo("PG&E");
            assertThat(b.dueDate()).isEqualTo(LocalDate.of(2026, 2, 2));
            assertThat(b.periodEnd()).isEqualTo(LocalDate.of(2026, 1, 8));
            assertThat(b.accountLast4()).isEqualTo("8905");
            assertThat(b.warnings()).isEmpty();
        });
    }

    @Test
    void extractsAndCategorisesLineItemsThatAddUpToTheTotal() {
        List<ParsedBill> bills = parser.parse(TestFixtures.text("pge-combined.txt"));

        assertThat(bills.get(1).charges())
                .extracting(ParsedCharge::description, ParsedCharge::category)
                .containsExactly(
                        tuple("Gas Procurement Charges", ChargeCategory.SUPPLY),
                        tuple("Gas Delivery Charges", ChargeCategory.DELIVERY),
                        tuple("Public Purpose Programs", ChargeCategory.FEE));
        assertThat(bills.get(0).charges()).extracting(ParsedCharge::category)
                .containsExactly(ChargeCategory.SUPPLY, ChargeCategory.DELIVERY, ChargeCategory.TAX);

        ParsedBill gas = parser.parse(TestFixtures.text("socalgas.txt")).getFirst();
        assertThat(gas.charges()).hasSize(3);
        assertThat(gas.charges().stream().map(ParsedCharge::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(gas.amount());
    }

    @Test
    void skipsBalancesPaymentsAndTotalsWhenExtractingCharges() {
        ParsedBill bill = parser.parse(TestFixtures.text("pge-electric.txt")).getFirst();
        assertThat(bill.charges()).extracting(ParsedCharge::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("61.22"), new BigDecimal("37.40"));
    }

    @Test
    void warnsWhenLineItemsDontAddUp() {
        String text = """
                Duke Energy
                Billing period 08/01/2026 - 08/31/2026
                Customer Charge      $14.00
                Energy Charge        $60.00
                Amount Due           $95.00
                Due Date 09/20/2026
                """;
        ParsedBill bill = parser.parse(text).getFirst();

        assertThat(bill.warnings()).singleElement().asString().contains("add up to $74.00");
        assertThat(bill.confidence()).isLessThan(new BigDecimal("0.9"));
    }

    @Test
    void flagsLikelyLayoutChangeWhenAProvidersUsualLabelsDisappear() {
        // PG&E statements have always said "Total Amount Due"; this one doesn't.
        String text = """
                PG&E
                Balance to pay: $98.62
                Billing Period: 07/31/2026 - 08/29/2026
                Total Usage 412 kWh
                """;
        ParsedBill bill = parser.parse(text).getFirst();

        assertThat(bill.warnings()).anySatisfy(w -> assertThat(w).contains("Total Amount Due").contains("layout"));
        assertThat(bill.amount()).isNull();
    }

    @Test
    void warnsAboutImplausibleValues() {
        ParsedBill bill = parser.parse("Amount due $12,500.00\nService period 01/01/2026 - 01/31/2026\n"
                + "Due date 01/15/2026").getFirst();
        assertThat(bill.warnings()).anySatisfy(w -> assertThat(w).contains("Unusually large"))
                .anySatisfy(w -> assertThat(w).contains("before the end of the service period"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "09/24/2026      | 2026-09-24",
            "9/4/26          | 2026-09-04",
            "2026-02-28      | 2026-02-28",
            "Sep 3, 2026     | 2026-09-03",
            "Sept. 3, 2026   | 2026-09-03",
            "September 30 2026 | 2026-09-30",
            "02/30/2026      | ",
    })
    void parsesCommonDateFormats(String raw, LocalDate expected) {
        assertThat(BillParser.parseDate(raw)).isEqualTo(expected);
    }
}
