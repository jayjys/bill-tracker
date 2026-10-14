package com.billtracker.ingest;

import com.billtracker.parsing.DocumentTextExtractor;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Reads a raw RFC 822 message (.eml upload, or raw MIME posted by an inbound-email provider). */
@Component
public class MimeEmailReader {

    private static final Session SESSION = Session.getInstance(new Properties());

    public InboundEmail read(byte[] raw) {
        try {
            MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(raw));
            Collector collector = new Collector();
            collector.walk(message);
            String body = collector.plain.isEmpty()
                    ? DocumentTextExtractor.htmlToText(collector.html.toString())
                    : collector.plain.toString();
            return new InboundEmail(fromName(message), message.getSubject(), body, collector.attachments);
        } catch (MessagingException | IOException e) {
            throw new DocumentTextExtractor.UnsupportedDocumentException("Couldn't read email: " + e.getMessage());
        }
    }

    private static String fromName(MimeMessage message) throws MessagingException {
        if (message.getFrom() == null || message.getFrom().length == 0) {
            return null;
        }
        if (message.getFrom()[0] instanceof InternetAddress address) {
            return address.getPersonal();
        }
        return null;
    }

    private static final class Collector {
        final StringBuilder plain = new StringBuilder();
        final StringBuilder html = new StringBuilder();
        final List<InboundEmail.Attachment> attachments = new ArrayList<>();

        void walk(Part part) throws MessagingException, IOException {
            String disposition = part.getDisposition();
            boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition)
                    || (part.getFileName() != null && !part.isMimeType("multipart/*"));
            if (isAttachment) {
                attachments.add(new InboundEmail.Attachment(
                        part.getFileName(), baseType(part.getContentType()), part.getInputStream().readAllBytes()));
            } else if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    walk(mp.getBodyPart(i));
                }
            } else if (part.isMimeType("message/rfc822")) {
                // A bill forwarded "as attachment" arrives as a nested message.
                walk((Part) part.getContent());
            } else if (part.isMimeType("text/plain")) {
                plain.append(part.getContent()).append('\n');
            } else if (part.isMimeType("text/html")) {
                html.append(part.getContent()).append('\n');
            }
        }

        private static String baseType(String contentType) {
            int semi = contentType.indexOf(';');
            return (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
        }
    }
}
