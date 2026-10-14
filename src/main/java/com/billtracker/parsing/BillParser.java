package com.billtracker.parsing;

import com.billtracker.bill.UtilityType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic parser for utility bill text. Bills vary wildly between providers, so
 * instead of per-provider templates it looks for labelled values ("Amount Due",
 * "Service Period", ...) and falls back to weaker signals. Anything it can't find
 * is left null and the bill is flagged for review.
 */
@Component
public class BillParser {

    private static final int F = Pattern.CASE_INSENSITIVE;

    private static final String MONTH =
            "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    private static final String DATE = "(?:\\d{1,2}/\\d{1,2}/\\d{2,4}"
            + "|\\d{4}-\\d{2}-\\d{2}"
            + "|" + MONTH + "\\s+\\d{1,2},?\\s+\\d{4})";
    private static final String MONEY = "\\$?\\s*(-?[\\d,]+\\.\\d{2})\\b";
    private static final String RANGE_SEP = "\\s*(?:-|–|—|to|through|thru)\\s*";

    private static final Pattern SLASH_DATE = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{2,4})");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern NAMED_DATE = Pattern.compile("([a-z]{3})[a-z]*\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})", F);

    /**
     * Ordered by preference. "Current charges" comes first because amount due can
     * include a past-due balance, which would double count in a cost trend.
     */
    private static final List<Pattern> AMOUNT_PATTERNS = List.of(
            labelled("total\\s+current\\s+charges|current\\s+charges|total\\s+new\\s+charges|new\\s+charges"),
            labelled("total\\s+amount\\s+due|amount\\s+due|total\\s+due|balance\\s+due|please\\s+pay"
                    + "|amount\\s+to\\s+pay|pay\\s+this\\s+amount"),
            labelled("total\\s+charges|total"));

    /** Within a utility section of a combined statement: "Total PG&E Gas Charges $35.10". */
    private static final Pattern SECTION_TOTAL = labelled("total[^\\n$\\d]{0,40}charges");

    private static final Pattern DUE_DATE = Pattern.compile(
            "(?:payment\\s+due(?:\\s+date)?|due\\s+date|due\\s+by|due\\s+on|pay\\s+by|amount\\s+due\\s+by)"
                    + "[^\\n]{0,25}?(" + DATE + ")", F);

    private static final Pattern LABELLED_PERIOD = Pattern.compile(
            "(?:(?:billing|service|statement)\\s+(?:period|dates)|service\\s+from|for\\s+service|billing\\s+cycle)"
                    + "[^\\n]{0,25}?(" + DATE + ")" + RANGE_SEP + "(" + DATE + ")", F);
    private static final Pattern ANY_PERIOD = Pattern.compile("(" + DATE + ")" + RANGE_SEP + "(" + DATE + ")", F);

    private static final String UNIT = "kwh|therms?|ccf|hcf|gallons|gal|gb";
    private static final Pattern USAGE_LABELLED = Pattern.compile(
            "(?:total\\s+usage|usage|used|consumption|you\\s+used)[^\\n$]{0,40}?"
                    + "(\\d[\\d,]*(?:\\.\\d+)?)\\s*(" + UNIT + ")\\b", F);
    private static final Pattern USAGE_ANY = Pattern.compile(
            "(?<![$.\\d])(\\d[\\d,]*(?:\\.\\d+)?)\\s*(" + UNIT + ")\\b", F);

    private static final Pattern ACCOUNT = Pattern.compile(
            "account\\s*(?:number|no\\.?|#)?[ \\t]*[:#]?[ \\t]*(\\d[\\d -]{3,24}\\d)", F);

    private static final Map<String, String> KNOWN_PROVIDERS = new LinkedHashMap<>();
    private static final Map<UtilityType, Pattern> TYPE_KEYWORDS = new EnumMap<>(UtilityType.class);

    static {
        KNOWN_PROVIDERS.put("pg&e|pacific gas (?:and|&) electric", "PG&E");
        KNOWN_PROVIDERS.put("con ?edison|consolidated edison", "Con Edison");
        KNOWN_PROVIDERS.put("southern california edison", "Southern California Edison");
        KNOWN_PROVIDERS.put("socalgas|southern california gas", "SoCalGas");
        KNOWN_PROVIDERS.put("duke energy", "Duke Energy");
        KNOWN_PROVIDERS.put("xcel energy", "Xcel Energy");
        KNOWN_PROVIDERS.put("dominion energy", "Dominion Energy");
        KNOWN_PROVIDERS.put("georgia power", "Georgia Power");
        KNOWN_PROVIDERS.put("florida power (?:and|&) light|\\bfpl\\b", "FPL");
        KNOWN_PROVIDERS.put("\\bcomed\\b|commonwealth edison", "ComEd");
        KNOWN_PROVIDERS.put("national grid", "National Grid");
        KNOWN_PROVIDERS.put("eversource", "Eversource");
        KNOWN_PROVIDERS.put("pse&g", "PSE&G");
        KNOWN_PROVIDERS.put("puget sound energy", "Puget Sound Energy");
        KNOWN_PROVIDERS.put("seattle city light", "Seattle City Light");
        KNOWN_PROVIDERS.put("ladwp|los angeles department of water", "LADWP");
        KNOWN_PROVIDERS.put("austin energy", "Austin Energy");
        KNOWN_PROVIDERS.put("ameren", "Ameren");
        KNOWN_PROVIDERS.put("dte energy", "DTE Energy");
        KNOWN_PROVIDERS.put("consumers energy", "Consumers Energy");
        KNOWN_PROVIDERS.put("entergy", "Entergy");
        KNOWN_PROVIDERS.put("american electric power|\\baep\\b", "AEP");
        KNOWN_PROVIDERS.put("txu energy", "TXU Energy");
        KNOWN_PROVIDERS.put("reliant", "Reliant");
        KNOWN_PROVIDERS.put("peoples gas", "Peoples Gas");
        KNOWN_PROVIDERS.put("nicor gas", "Nicor Gas");
        KNOWN_PROVIDERS.put("american water", "American Water");
        KNOWN_PROVIDERS.put("xfinity|comcast", "Xfinity");
        KNOWN_PROVIDERS.put("spectrum", "Spectrum");
        KNOWN_PROVIDERS.put("verizon", "Verizon");
        KNOWN_PROVIDERS.put("t-mobile", "T-Mobile");
        KNOWN_PROVIDERS.put("at&t", "AT&T");
        KNOWN_PROVIDERS.put("google fiber", "Google Fiber");
        KNOWN_PROVIDERS.put("waste management", "Waste Management");
        KNOWN_PROVIDERS.put("republic services", "Republic Services");

        TYPE_KEYWORDS.put(UtilityType.ELECTRIC, Pattern.compile("electric|kwh", F));
        TYPE_KEYWORDS.put(UtilityType.GAS, Pattern.compile("natural gas|therms?\\b|gas service|gas charges|gas usage", F));
        TYPE_KEYWORDS.put(UtilityType.WATER, Pattern.compile("water|sewer|gallons", F));
        TYPE_KEYWORDS.put(UtilityType.INTERNET, Pattern.compile("internet|broadband|wi-?fi|mbps|fiber", F));
        TYPE_KEYWORDS.put(UtilityType.PHONE, Pattern.compile("wireless|mobile|cellular|talk (?:&|and) text", F));
        TYPE_KEYWORDS.put(UtilityType.TRASH, Pattern.compile("trash|garbage|refuse|recycling|waste", F));
    }

    /** Bump when parsing logic improves; older NEEDS_REVIEW bills are re-parsed by a scheduled job. */
    public static final int VERSION = 2;

    public List<ParsedBill> parse(String text) {
        return parse(text, null);
    }

    /**
     * Parses a statement into one bill per utility it covers (usually one).
     *
     * @param providerHint fallback provider name when none is recognised in the text,
     *                     e.g. the display name of the email sender
     */
    public List<ParsedBill> parse(String text, String providerHint) {
        String t = normalize(text);
        Optional<ProviderProfile> profile = ProviderProfile.ALL.stream().filter(p -> p.matches(t)).findFirst();

        // Statement-level fields, shared by every section.
        String provider = profile.map(ProviderProfile::provider)
                .or(() -> findProvider(t))
                .or(() -> Optional.ofNullable(blankToNull(providerHint)))
                .orElseGet(() -> headingLine(t));
        LocalDate dueDate = firstDate(DUE_DATE, t);
        LocalDate[] statementPeriod = findPeriod(t);
        String accountLast4 = findAccountLast4(t);

        List<String> layoutWarnings = profile.map(p -> p.missingAnchors(t).stream()
                        .map(a -> "Expected \"" + a + "\" on a " + p.provider()
                                + " bill but didn't find it — the layout may have changed")
                        .toList())
                .orElse(List.of());

        List<ProviderProfile.Section> sections = profile.map(p -> p.sections(t)).orElse(List.of());
        if (sections.size() < 2) {
            Usage usage = findUsage(t);
            UtilityType type = detectType(t, usage == null ? null : usage.unit());
            return List.of(build(provider, type, findAmount(t), dueDate, statementPeriod, usage, accountLast4,
                    ChargeExtractor.extract(t), layoutWarnings));
        }

        List<ParsedBill> bills = new ArrayList<>();
        for (ProviderProfile.Section section : sections) {
            String st = section.text();
            LocalDate[] period = findPeriod(st);
            if (period[1] == null) {
                period = statementPeriod;
            }
            BigDecimal amount = firstMoney(SECTION_TOTAL, st);
            bills.add(build(provider, section.type(), amount != null ? amount : findAmount(st), dueDate, period,
                    findUsage(st), accountLast4, ChargeExtractor.extract(st), layoutWarnings));
        }
        return bills;
    }

    private static ParsedBill build(String provider, UtilityType type, BigDecimal amount, LocalDate dueDate,
                                    LocalDate[] period, Usage usage, String accountLast4,
                                    List<ParsedCharge> charges, List<String> layoutWarnings) {
        List<String> warnings = new ArrayList<>(layoutWarnings);
        warnings.addAll(Validator.check(amount, dueDate, period[0], period[1], charges));

        double score = 0;
        score += amount != null ? 0.40 : 0;
        score += period[1] != null || dueDate != null ? 0.20 : 0;
        score += type != UtilityType.OTHER ? 0.15 : 0;
        score += usage != null ? 0.15 : 0;
        score += provider != null ? 0.10 : 0;
        // Each failed self-check costs confidence, so dubious bills sort to the review queue.
        score = Math.max(0, score - 0.15 * warnings.size());

        return new ParsedBill(
                provider,
                type,
                amount,
                dueDate,
                period[0],
                period[1],
                usage == null ? null : usage.quantity(),
                usage == null ? null : usage.unit(),
                accountLast4,
                BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP),
                charges,
                List.copyOf(warnings));
    }

    private static BigDecimal firstMoney(Pattern p, String t) {
        Matcher m = p.matcher(t);
        while (m.find()) {
            BigDecimal value = money(m.group(1));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Pattern labelled(String labels) {
        return Pattern.compile("(?:" + labels + ")[^\\n$]{0,40}?" + MONEY, F);
    }

    /** PDF extraction produces non-breaking spaces and odd whitespace; flatten it. */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(' ', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+", " ");
    }

    private static BigDecimal findAmount(String t) {
        for (Pattern p : AMOUNT_PATTERNS) {
            BigDecimal value = firstMoney(p, t);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static LocalDate[] findPeriod(String t) {
        for (Pattern p : List.of(LABELLED_PERIOD, ANY_PERIOD)) {
            Matcher m = p.matcher(t);
            while (m.find()) {
                LocalDate start = parseDate(m.group(1));
                LocalDate end = parseDate(m.group(2));
                // Sanity check: a billing period runs forward and is no longer than ~3 months.
                if (start != null && end != null && end.isAfter(start) && start.plusDays(100).isAfter(end)) {
                    return new LocalDate[] {start, end};
                }
            }
        }
        return new LocalDate[] {null, null};
    }

    private static Usage findUsage(String t) {
        for (Pattern p : List.of(USAGE_LABELLED, USAGE_ANY)) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                BigDecimal qty = number(m.group(1));
                if (qty != null && qty.signum() > 0) {
                    if (qty.scale() > 3) {
                        qty = qty.setScale(3, RoundingMode.HALF_UP);
                    }
                    return new Usage(qty, canonicalUnit(m.group(2)));
                }
            }
        }
        return null;
    }

    private static Optional<String> findProvider(String t) {
        for (Map.Entry<String, String> e : KNOWN_PROVIDERS.entrySet()) {
            if (Pattern.compile(e.getKey(), F).matcher(t).find()) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Last resort for unknown providers: bills usually open with the issuer's name,
     * so take the first short line that reads like a name rather than data.
     */
    private static String headingLine(String t) {
        return t.lines()
                .map(String::strip)
                .filter(line -> line.length() >= 3 && line.length() <= 60)
                .limit(3)
                .filter(line -> line.chars().filter(Character::isLetter).count() >= line.length() * 0.6)
                .filter(line -> !line.toLowerCase(Locale.ROOT).matches(".*(statement|invoice|bill|thank|dear|page).*"))
                .findFirst()
                .orElse(null);
    }

    static UtilityType detectType(String t, String unit) {
        if (unit != null) {
            switch (unit) {
                case "kWh" -> { return UtilityType.ELECTRIC; }
                case "therms" -> { return UtilityType.GAS; }
                case "gallons", "HCF" -> { return UtilityType.WATER; }
                case "GB" -> { return UtilityType.INTERNET; }
                default -> { } // CCF is used for both gas and water; decide by keywords
            }
        }
        UtilityType best = UtilityType.OTHER;
        int bestCount = 0;
        for (Map.Entry<UtilityType, Pattern> e : TYPE_KEYWORDS.entrySet()) {
            int count = (int) e.getValue().matcher(t).results().count();
            if (count > bestCount) {
                best = e.getKey();
                bestCount = count;
            }
        }
        return best;
    }

    private static String findAccountLast4(String t) {
        Matcher m = ACCOUNT.matcher(t);
        if (!m.find()) {
            return null;
        }
        String digits = m.group(1).replaceAll("\\D", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }

    private static LocalDate firstDate(Pattern p, String t) {
        Matcher m = p.matcher(t);
        while (m.find()) {
            LocalDate d = parseDate(m.group(1));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    static LocalDate parseDate(String raw) {
        String s = raw.trim();
        try {
            Matcher m = SLASH_DATE.matcher(s);
            if (m.matches()) {
                int year = Integer.parseInt(m.group(3));
                if (year < 100) {
                    year += 2000;
                }
                return LocalDate.of(year, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
            m = ISO_DATE.matcher(s);
            if (m.matches()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
            }
            m = NAMED_DATE.matcher(s);
            if (m.matches()) {
                int month = monthNumber(m.group(1));
                return month == 0 ? null
                        : LocalDate.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(2)));
            }
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static int monthNumber(String abbrev) {
        String months = "janfebmaraprmayjunjulaugsepoctnovdec";
        int idx = months.indexOf(abbrev.toLowerCase(Locale.ROOT));
        return idx < 0 || idx % 3 != 0 ? 0 : idx / 3 + 1;
    }

    static BigDecimal money(String raw) {
        BigDecimal value = number(raw);
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal number(String raw) {
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String canonicalUnit(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "kwh" -> "kWh";
            case "therm", "therms" -> "therms";
            case "ccf" -> "CCF";
            case "hcf" -> "HCF";
            case "gal", "gallons" -> "gallons";
            case "gb" -> "GB";
            default -> unit;
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private record Usage(BigDecimal quantity, String unit) {
    }
}
