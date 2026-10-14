package com.billtracker.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "billtracker.inbound")
public record InboundProperties(String domain, String secret) {

    public String addressFor(String token) {
        return "bills+" + token + "@" + domain;
    }
}
