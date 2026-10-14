package com.billtracker.parsing;

import com.billtracker.bill.ChargeCategory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls line items ("Gas Transmission Charge ... $48.17") out of bill text and
 * sorts them into categories. Totals, balances and payments are skipped since
 * they aren't charges for this period.
 */
final class ChargeExtractor {

    private static final int F = Pattern.CASE_INSENSITIVE;

    private static final Pattern LINE = Pattern.compile(
            "^\\s*([A-Za-z][^$\\n]{1,80}?[A-Za-z)])[\\s.:]+(-?\\s?\\$?\\s?-?[\\d,]+\\.\\d{2})\\s*$",
            Pattern.MULTILINE);

    private static final Pattern NOT_A_CHARGE = Pattern.compile(
            "total|subtotal|amount due|balance|payments?\\s+(?:received|thank)|payment\\(s\\)|previous"
                    + "|please pay|pay this|amount enclosed|new charges|current charges|due date|statement",
            F);

    private static final Pattern DATE_LIKE = Pattern.compile("\\d{1,2}/\\d{1,2}");

    private static final List<Rule> RULES = List.of(
            new Rule(ChargeCategory.CREDIT, Pattern.compile("credit|rebate|discount|refund", F)),
            new Rule(ChargeCategory.TAX, Pattern.compile("\\btax", F)),
            new Rule(ChargeCategory.FEE, Pattern.compile(
                    "\\bfee|surcharge|late|public purpose|program|regulatory|recovery|adjustment", F)),
            new Rule(ChargeCategory.DELIVERY, Pattern.compile(
                    "delivery|transmission|distribution|customer charge|basic|service charge|meter|sewer|base charge",
                    F)),
            new Rule(ChargeCategory.SUPPLY, Pattern.compile(
                    "generation|supply|commodity|energy charge|clean power|procurement|usage charge"
                            + "|water charge|gas charge|electricity charge|internet|plan", F)));

    private record Rule(ChargeCategory category, Pattern pattern) {
    }

    private ChargeExtractor() {
    }

    static List<ParsedCharge> extract(String text) {
        List<ParsedCharge> charges = new ArrayList<>();
        Matcher m = LINE.matcher(text);
        while (m.find()) {
            String description = m.group(1).replaceAll("\\s+", " ").strip();
            if (NOT_A_CHARGE.matcher(description).find() || DATE_LIKE.matcher(description).find()) {
                continue;
            }
            String raw = m.group(2).replace(" ", "");
            BigDecimal amount = BillParser.money(raw.replace("$", "").replace("-", ""));
            if (amount == null) {
                continue;
            }
            if (raw.contains("-")) {
                amount = amount.negate();
            }
            charges.add(new ParsedCharge(description, categorize(description, amount), amount));
        }
        return charges;
    }

    static ChargeCategory categorize(String description, BigDecimal amount) {
        if (amount.signum() < 0) {
            return ChargeCategory.CREDIT;
        }
        return RULES.stream()
                .filter(r -> r.pattern().matcher(description).find())
                .map(Rule::category)
                .findFirst()
                .orElse(ChargeCategory.OTHER);
    }
}
