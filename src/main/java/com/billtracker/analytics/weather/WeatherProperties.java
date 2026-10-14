package com.billtracker.analytics.weather;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "billtracker.weather")
public record WeatherProperties(
        String archiveUrl,
        String geocodingUrl,
        Duration timeout,
        int maxAttempts,
        int historyYears) {
}
