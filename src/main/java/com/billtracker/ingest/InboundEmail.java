package com.billtracker.ingest;

import java.util.List;

/** An email reduced to what bill ingestion needs. */
public record InboundEmail(String fromName, String subject, String bodyText, List<Attachment> attachments) {

    public record Attachment(String filename, String contentType, byte[] content) {
    }
}
