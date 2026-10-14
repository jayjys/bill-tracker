package com.billtracker.analytics.weather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Open-Meteo (free, no API key): ERA5-based historical archive and place search.
 * Calls time out and are retried with backoff; persistent failure surfaces as
 * {@link WeatherUnavailableException} so callers can fall back to weather-free models.
 */
@Component
public class OpenMeteoClient implements WeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoClient.class);

    private final WeatherProperties props;
    private final RestClient http;

    public OpenMeteoClient(WeatherProperties props) {
        this.props = props;
        HttpClient client = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(props.timeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeoResponse(List<GeoResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeoResult(String name, String admin1, String country, double latitude, double longitude) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ArchiveResponse(Daily daily) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Daily(List<String> time,
                 @JsonProperty("temperature_2m_mean") List<Double> meanC) {
    }

    @Override
    public Optional<Place> geocode(String query) {
        GeoResponse response = withRetry("geocode", () -> http.get()
                .uri(props.geocodingUrl() + "?count=1&language=en&format=json&name={q}", query)
                .retrieve()
                .body(GeoResponse.class));
        if (response == null || response.results() == null || response.results().isEmpty()) {
            return Optional.empty();
        }
        GeoResult r = response.results().getFirst();
        String name = r.name() + (r.admin1() != null ? ", " + r.admin1() : "")
                + (r.country() != null ? ", " + r.country() : "");
        return Optional.of(new Place(name, BigDecimal.valueOf(r.latitude()), BigDecimal.valueOf(r.longitude())));
    }

    @Override
    public List<DailyTemp> dailyMeans(BigDecimal latitude, BigDecimal longitude, LocalDate from, LocalDate to) {
        ArchiveResponse response = withRetry("archive", () -> http.get()
                .uri(props.archiveUrl() + "?latitude={lat}&longitude={lon}&start_date={from}&end_date={to}"
                                + "&daily=temperature_2m_mean&timezone=auto",
                        latitude, longitude, from, to)
                .retrieve()
                .body(ArchiveResponse.class));
        List<DailyTemp> out = new ArrayList<>();
        if (response == null || response.daily() == null || response.daily().time() == null) {
            return out;
        }
        Daily d = response.daily();
        for (int i = 0; i < d.time().size(); i++) {
            Double t = i < d.meanC().size() ? d.meanC().get(i) : null;
            if (t != null) { // the most recent days are null until the reanalysis catches up
                out.add(new DailyTemp(LocalDate.parse(d.time().get(i)), t));
            }
        }
        return out;
    }

    private <T> T withRetry(String what, Supplier<T> call) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            try {
                return call.get();
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != 429) {
                    throw new WeatherUnavailableException("Open-Meteo rejected " + what + ": " + e.getStatusCode(), e);
                }
                last = e;
            } catch (RestClientException e) {
                last = e;
            }
            log.warn("Open-Meteo {} attempt {}/{} failed: {}", what, attempt, props.maxAttempts(), last.getMessage());
            sleep(500L * (1L << (attempt - 1)));
        }
        throw new WeatherUnavailableException("Open-Meteo " + what + " failed after retries", last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
