package com.billtracker.ingest;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MimeEmailReaderTest {

    private final MimeEmailReader reader = new MimeEmailReader();

    @Test
    void readsBodyAndPdfAttachment() throws Exception {
        byte[] raw = email(true);

        InboundEmail email = reader.read(raw);

        assertThat(email.fromName()).isEqualTo("Xfinity");
        assertThat(email.subject()).isEqualTo("Your bill is ready");
        assertThat(email.bodyText()).contains("Your statement is attached");
        assertThat(email.attachments()).singleElement().satisfies(a -> {
            assertThat(a.filename()).isEqualTo("statement.pdf");
            assertThat(a.contentType()).isEqualTo("application/pdf");
            assertThat(new String(a.content(), StandardCharsets.US_ASCII)).startsWith("%PDF");
        });
    }

    @Test
    void fallsBackToHtmlBodyWhenThereIsNoPlainText() throws Exception {
        InboundEmail email = reader.read(email(false));
        assertThat(email.bodyText()).contains("Amount due").contains("$70.00").doesNotContain("<td>");
        assertThat(email.attachments()).isEmpty();
    }

    static byte[] email(boolean withAttachment) throws Exception {
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()));
        msg.setFrom(new InternetAddress("billing@xfinity.com", "Xfinity"));
        msg.setSubject("Your bill is ready");
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        if (withAttachment) {
            body.setText("Your statement is attached.", "UTF-8");
        } else {
            body.setContent("<table><tr><td>Amount due</td><td>$70.00</td></tr></table>", "text/html; charset=UTF-8");
        }
        mixed.addBodyPart(body);
        if (withAttachment) {
            MimeBodyPart pdf = new MimeBodyPart();
            pdf.setDataHandler(new DataHandler(
                    new ByteArrayDataSource("%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII), "application/pdf")));
            pdf.setFileName("statement.pdf");
            pdf.setDisposition(MimeBodyPart.ATTACHMENT);
            mixed.addBodyPart(pdf);
        }
        msg.setContent(mixed);
        msg.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        msg.writeTo(out);
        return out.toByteArray();
    }
}
