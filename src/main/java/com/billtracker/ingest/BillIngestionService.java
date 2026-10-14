package com.billtracker.ingest;

import com.billtracker.analytics.anomaly.Alert;
import com.billtracker.analytics.anomaly.AlertKind;
import com.billtracker.analytics.anomaly.AlertRepository;
import com.billtracker.analytics.anomaly.AnomalyService;
import com.billtracker.bill.Bill;
import com.billtracker.bill.BillCharge;
import com.billtracker.bill.BillRepository;
import com.billtracker.bill.BillSource;
import com.billtracker.bill.BillService;
import com.billtracker.bill.BillStatus;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.household.Household;
import com.billtracker.parsing.BillParser;
import com.billtracker.parsing.DocumentTextExtractor;
import com.billtracker.parsing.DocumentTextExtractor.Kind;
import com.billtracker.parsing.DocumentTextExtractor.UnsupportedDocumentException;
import com.billtracker.parsing.ParsedBill;
import com.billtracker.parsing.ParsedCharge;
import com.billtracker.user.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Turns uploaded files and inbound emails into stored, parsed bills. */
@Service
public class BillIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BillIngestionService.class);
    private static final int MAX_RAW_TEXT = 100_000;
    private static final Set<Kind> BILL_DOCUMENTS = Set.of(Kind.PDF, Kind.TEXT, Kind.HTML);

    private final BillRepository bills;
    private final BillService billService;
    private final BillParser parser;
    private final DocumentTextExtractor extractor;
    private final MimeEmailReader emailReader;
    private final AlertRepository alerts;
    private final AnomalyService anomalies;

    public BillIngestionService(BillRepository bills, BillService billService, BillParser parser,
                                DocumentTextExtractor extractor, MimeEmailReader emailReader, AlertRepository alerts,
                                AnomalyService anomalies) {
        this.bills = bills;
        this.billService = billService;
        this.parser = parser;
        this.extractor = extractor;
        this.emailReader = emailReader;
        this.alerts = alerts;
        this.anomalies = anomalies;
    }

    public enum Result { CREATED, DUPLICATE, FAILED }

    public record Outcome(String name, Result result, Bill bill, String message) {
    }

    @Transactional
    public List<Outcome> ingestFile(AppUser user, String filename, String contentType, byte[] bytes,
                                    BillSource source) {
        Kind kind = extractor.detect(filename, contentType, bytes);
        try {
            if (kind == Kind.EMAIL) {
                return ingestEmail(user, emailReader.read(bytes), source);
            }
            String text = extractor.extractText(kind, bytes);
            return List.of(store(user, filename, text, sha256(bytes), source, null));
        } catch (UnsupportedDocumentException e) {
            return List.of(new Outcome(filename, Result.FAILED, null, e.getMessage()));
        }
    }

    /**
     * Bill documents attached to the email each become a bill. With no usable
     * attachment, the email body itself is treated as the bill (e.g. e-bill notices).
     */
    @Transactional
    public List<Outcome> ingestEmail(AppUser user, InboundEmail email, BillSource source) {
        List<Outcome> outcomes = new ArrayList<>();
        for (InboundEmail.Attachment att : email.attachments()) {
            Kind kind = extractor.detect(att.filename(), att.contentType(), att.content());
            if (kind == Kind.EMAIL) {
                outcomes.addAll(ingestEmail(user, emailReader.read(att.content()), source));
            } else if (BILL_DOCUMENTS.contains(kind)) {
                try {
                    String text = extractor.extractText(kind, att.content());
                    outcomes.add(store(user, att.filename(), text, sha256(att.content()), source, email.fromName()));
                } catch (UnsupportedDocumentException e) {
                    outcomes.add(new Outcome(att.filename(), Result.FAILED, null, e.getMessage()));
                }
            }
        }
        if (outcomes.isEmpty()) {
            String subject = email.subject() == null ? "(no subject)" : email.subject();
            String text = subject + "\n" + (email.bodyText() == null ? "" : email.bodyText());
            if (text.isBlank()) {
                return List.of(new Outcome(subject, Result.FAILED, null, "Email had no readable content"));
            }
            outcomes.add(store(user, subject, text, sha256(text.getBytes(StandardCharsets.UTF_8)), source,
                    email.fromName()));
        }
        return outcomes;
    }

    private Outcome store(AppUser user, String name, String text, String sha, BillSource source,
                          String providerHint) {
        Household household = user.getHousehold();
        if (bills.existsByHouseholdIdAndContentSha256(household.getId(), sha)) {
            return new Outcome(name, Result.DUPLICATE, null, "Already imported");
        }
        List<ParsedBill> parsed = parser.parse(text, providerHint);
        List<Bill> saved = new ArrayList<>();
        for (ParsedBill p : parsed) {
            UtilityAccount account = billService.account(household, truncate(p.provider(), 120), p.utilityType(),
                    p.accountLast4());
            Bill bill = new Bill(user, account, source);
            bill.setOriginalFilename(truncate(name, 255));
            bill.setContentSha256(sha);
            bill.setRawText(truncate(text, MAX_RAW_TEXT));
            apply(bill, p);
            Bill b = bills.save(bill);
            saved.add(b);
            checkForLayoutChange(b, p);
            log.info("Ingested bill id={} household={} source={} type={} confidence={} status={}",
                    b.getId(), household.getId(), source, p.utilityType(), p.confidence(), b.getStatus());
        }
        anomalies.scan(household);

        Bill first = saved.getFirst();
        boolean allOk = saved.stream().allMatch(b -> b.getStatus() == BillStatus.PARSED);
        String message = (saved.size() > 1 ? "Split into " + saved.size() + " bills (" + saved.stream()
                .map(b -> b.getUtilityType().label().toLowerCase()).reduce((a, b) -> a + " + " + b).orElse("")
                + "). " : "")
                + (allOk ? "Parsed" : "Needs review — some fields couldn't be read or didn't add up");
        return new Outcome(name, Result.CREATED, first, message);
    }

    /** Copies parser output onto a bill, replacing earlier parsed values and line items. */
    static void apply(Bill bill, ParsedBill p) {
        bill.setAmount(p.amount());
        bill.setDueDate(p.dueDate());
        bill.setPeriodStart(p.periodStart());
        bill.setPeriodEnd(p.periodEnd());
        bill.setUsageQuantity(p.usageQuantity());
        bill.setUsageUnit(p.usageUnit());
        bill.setParseConfidence(p.confidence());
        bill.setParserVersion(BillParser.VERSION);
        bill.setParseWarnings(p.warnings().isEmpty() ? null : truncate(String.join("; ", p.warnings()), 1000));
        bill.setStatus(p.isComplete() && p.warnings().isEmpty() ? BillStatus.PARSED : BillStatus.NEEDS_REVIEW);
        List<BillCharge> charges = new ArrayList<>();
        int line = 1;
        for (ParsedCharge c : p.charges()) {
            charges.add(new BillCharge(bill, line++, truncate(c.description(), 200), c.category(), c.amount()));
        }
        bill.replaceCharges(charges);
    }

    /**
     * If a provider's bills have been reading cleanly and this one doesn't, the provider
     * most likely redesigned its statement. Flag it so the parser can be updated, rather
     * than letting bad numbers flow into the trends.
     */
    private void checkForLayoutChange(Bill bill, ParsedBill parsed) {
        boolean bad = !parsed.isComplete() || parsed.warnings().stream().anyMatch(w -> w.contains("layout"));
        if (!bad || bills.countWellParsedFromAccount(bill.getAccount().getId(), bill.getId()) < 2) {
            return;
        }
        alerts.save(new Alert(bill, AlertKind.PARSER_FORMAT_CHANGE, Alert.Severity.WARNING,
                bill.getProvider() + " bill didn't read like the previous ones",
                "Earlier " + bill.getProvider() + " bills parsed cleanly but this one is missing "
                        + (parsed.amount() == null ? "the amount" : "expected fields")
                        + ". The provider may have changed its bill layout; please check the values."));
        log.warn("Possible layout change for provider={} bill={} warnings={}", bill.getProvider(), bill.getId(),
                parsed.warnings());
    }

    /**
     * Re-reads a stored bill with the current parser. Used when the parser improves; only
     * replaces values if the new parse is at least as confident. Returns true if updated.
     */
    @Transactional
    public boolean reparse(Bill stale) {
        // Reload inside this transaction; the job hands over detached entities.
        Bill bill = bills.findById(stale.getId()).orElseThrow();
        List<ParsedBill> parsed = parser.parse(bill.getRawText(), bill.getProvider());
        ParsedBill match = parsed.stream().filter(p -> p.utilityType() == bill.getUtilityType()).findFirst()
                .orElse(parsed.size() == 1 ? parsed.getFirst() : null);
        if (match == null || match.confidence().compareTo(bill.getParseConfidence()) < 0) {
            bill.setParserVersion(BillParser.VERSION);
            bills.save(bill);
            return false;
        }
        apply(bill, match);
        bills.save(bill);
        return true;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
