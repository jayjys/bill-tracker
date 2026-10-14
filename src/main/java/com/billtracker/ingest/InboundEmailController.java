package com.billtracker.ingest;

import com.billtracker.bill.BillSource;
import com.billtracker.parsing.DocumentTextExtractor;
import com.billtracker.user.AppUser;
import com.billtracker.user.UserRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Webhook for forwarded bills. Point your email provider's inbound route for
 * {@code bills+*@<domain>} at {@code POST /api/inbound/email?secret=...}. Accepts:
 * <ul>
 *   <li>multipart form posts (SendGrid Inbound Parse, Mailgun routes), raw MIME or parsed fields</li>
 *   <li>Postmark-style JSON</li>
 *   <li>a raw {@code message/rfc822} body with {@code ?to=}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/inbound/email")
public class InboundEmailController {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailController.class);
    private static final Pattern TOKEN = Pattern.compile("([a-f0-9]{16})@", Pattern.CASE_INSENSITIVE);

    private final InboundProperties properties;
    private final UserRepository users;
    private final MimeEmailReader emailReader;
    private final BillIngestionService ingestion;

    public InboundEmailController(InboundProperties properties, UserRepository users,
                                  MimeEmailReader emailReader, BillIngestionService ingestion) {
        this.properties = properties;
        this.users = users;
        this.emailReader = emailReader;
        this.ingestion = ingestion;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> multipart(
            MultipartHttpServletRequest request,
            @RequestHeader(value = "X-Inbound-Secret", required = false) String headerSecret,
            @RequestParam(value = "secret", required = false) String querySecret) throws IOException {
        if (!authorized(headerSecret, querySecret)) {
            return status(HttpStatus.UNAUTHORIZED, "bad secret");
        }
        String recipient = first(request.getParameter("recipient"), request.getParameter("to"));
        Optional<AppUser> user = userFor(recipient);
        if (user.isEmpty()) {
            return status(HttpStatus.NOT_FOUND, "unknown recipient");
        }

        String rawMime = first(request.getParameter("email"), request.getParameter("body-mime"));
        InboundEmail email;
        if (rawMime != null) {
            email = emailReader.read(rawMime.getBytes(StandardCharsets.UTF_8));
        } else {
            List<InboundEmail.Attachment> attachments = new ArrayList<>();
            MultiValueMap<String, MultipartFile> files = request.getMultiFileMap();
            for (List<MultipartFile> parts : files.values()) {
                for (MultipartFile f : parts) {
                    attachments.add(new InboundEmail.Attachment(f.getOriginalFilename(), f.getContentType(),
                            f.getBytes()));
                }
            }
            email = new InboundEmail(
                    request.getParameter("from"),
                    request.getParameter("subject"),
                    first(request.getParameter("body-plain"), request.getParameter("text"),
                            htmlText(first(request.getParameter("body-html"), request.getParameter("html")))),
                    attachments);
        }
        return ingest(user.get(), email);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> json(
            @RequestBody PostmarkPayload payload,
            @RequestHeader(value = "X-Inbound-Secret", required = false) String headerSecret,
            @RequestParam(value = "secret", required = false) String querySecret) {
        if (!authorized(headerSecret, querySecret)) {
            return status(HttpStatus.UNAUTHORIZED, "bad secret");
        }
        Optional<AppUser> user = userFor(first(payload.originalRecipient(), payload.to()));
        if (user.isEmpty()) {
            return status(HttpStatus.NOT_FOUND, "unknown recipient");
        }
        List<InboundEmail.Attachment> attachments = payload.attachments() == null ? List.of()
                : payload.attachments().stream()
                .map(a -> new InboundEmail.Attachment(a.name(), a.contentType(),
                        Base64.getMimeDecoder().decode(a.content())))
                .toList();
        InboundEmail email = new InboundEmail(payload.fromName(), payload.subject(),
                first(payload.textBody(), htmlText(payload.htmlBody())), attachments);
        return ingest(user.get(), email);
    }

    @PostMapping(consumes = "message/rfc822")
    public ResponseEntity<Map<String, Object>> raw(
            @RequestBody byte[] body,
            @RequestParam("to") String to,
            @RequestHeader(value = "X-Inbound-Secret", required = false) String headerSecret,
            @RequestParam(value = "secret", required = false) String querySecret) {
        if (!authorized(headerSecret, querySecret)) {
            return status(HttpStatus.UNAUTHORIZED, "bad secret");
        }
        Optional<AppUser> user = userFor(to);
        if (user.isEmpty()) {
            return status(HttpStatus.NOT_FOUND, "unknown recipient");
        }
        return ingest(user.get(), emailReader.read(body));
    }

    private ResponseEntity<Map<String, Object>> ingest(AppUser user, InboundEmail email) {
        List<BillIngestionService.Outcome> outcomes = ingestion.ingestEmail(user, email, BillSource.EMAIL);
        log.info("Inbound email for user={} produced {} outcome(s)", user.getId(), outcomes.size());
        List<Map<String, Object>> results = outcomes.stream()
                .map(o -> Map.<String, Object>of(
                        "name", String.valueOf(o.name()),
                        "result", o.result().name(),
                        "billId", o.bill() == null ? -1 : o.bill().getId()))
                .toList();
        return ResponseEntity.ok(Map.of("results", results));
    }

    private boolean authorized(String headerSecret, String querySecret) {
        String given = headerSecret != null ? headerSecret : querySecret;
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    private Optional<AppUser> userFor(String recipient) {
        if (recipient == null) {
            return Optional.empty();
        }
        Matcher m = TOKEN.matcher(recipient);
        while (m.find()) {
            Optional<AppUser> user = users.findByInboundToken(m.group(1).toLowerCase());
            if (user.isPresent()) {
                return user;
            }
        }
        return Optional.empty();
    }

    private static ResponseEntity<Map<String, Object>> status(HttpStatus status, String error) {
        return ResponseEntity.status(status).body(Map.of("error", error));
    }

    private static String htmlText(String html) {
        return html == null ? null : DocumentTextExtractor.htmlToText(html);
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostmarkPayload(
            @JsonProperty("To") String to,
            @JsonProperty("OriginalRecipient") String originalRecipient,
            @JsonProperty("FromName") String fromName,
            @JsonProperty("Subject") String subject,
            @JsonProperty("TextBody") String textBody,
            @JsonProperty("HtmlBody") String htmlBody,
            @JsonProperty("Attachments") List<PostmarkAttachment> attachments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostmarkAttachment(
            @JsonProperty("Name") String name,
            @JsonProperty("ContentType") String contentType,
            @JsonProperty("Content") String content) {
    }
}
