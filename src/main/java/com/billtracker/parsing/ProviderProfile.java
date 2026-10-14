package com.billtracker.parsing;

import com.billtracker.bill.UtilityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Knowledge about one provider's statement layout. Most providers are handled by the
 * generic parser; a profile is only needed when a layout needs help, e.g. a combined
 * electric + gas statement that has to be split into per-utility sections.
 *
 * <p>{@code anchors} are labels every statement from the provider has had. When one
 * goes missing, the provider has probably changed its layout and the parse is flagged.
 */
public record ProviderProfile(
        String provider,
        Pattern detect,
        Map<UtilityType, Pattern> sectionHeaders,
        List<Anchor> anchors) {

    public record Anchor(String label, Pattern pattern) {
    }

    public record Section(UtilityType type, String text) {
    }

    public boolean matches(String text) {
        return detect.matcher(text).find();
    }

    /**
     * Splits the statement at each section header. Text before the first header (account
     * number, due date, total due) is shared, so it is returned separately by the caller.
     */
    public List<Section> sections(String text) {
        record Hit(UtilityType type, int start) {
        }
        List<Hit> hits = new ArrayList<>();
        sectionHeaders.forEach((type, header) -> {
            Matcher m = header.matcher(text);
            if (m.find()) {
                hits.add(new Hit(type, m.start()));
            }
        });
        hits.sort(Comparator.comparingInt(Hit::start));
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            int end = i + 1 < hits.size() ? hits.get(i + 1).start() : text.length();
            sections.add(new Section(hits.get(i).type(), text.substring(hits.get(i).start(), end)));
        }
        return sections;
    }

    public List<String> missingAnchors(String text) {
        return anchors.stream().filter(a -> !a.pattern().matcher(text).find()).map(Anchor::label).toList();
    }

    private static final int F = Pattern.CASE_INSENSITIVE;

    public static final List<ProviderProfile> ALL = List.of(
            new ProviderProfile(
                    "PG&E",
                    Pattern.compile("pg&e|pacific gas (?:and|&) electric", F),
                    Map.of(
                            UtilityType.ELECTRIC, Pattern.compile("^.*(?:details of )?pg&e electric delivery charges\\s*$", F | Pattern.MULTILINE),
                            UtilityType.GAS, Pattern.compile("^.*(?:details of )?pg&e gas charges\\s*$", F | Pattern.MULTILINE)),
                    List.of(
                            new Anchor("Total Amount Due", Pattern.compile("total amount due", F)),
                            new Anchor("Due Date", Pattern.compile("due date", F)))),
            new ProviderProfile(
                    "Con Edison",
                    Pattern.compile("con ?edison|consolidated edison", F),
                    Map.of(
                            UtilityType.ELECTRIC, Pattern.compile("^\\s*(?:your )?electricity charges\\s*$", F | Pattern.MULTILINE),
                            UtilityType.GAS, Pattern.compile("^\\s*(?:your )?gas charges\\s*$", F | Pattern.MULTILINE)),
                    List.of(
                            new Anchor("Total amount due", Pattern.compile("total amount due", F)),
                            new Anchor("Billing period", Pattern.compile("billing period", F)))));
}
