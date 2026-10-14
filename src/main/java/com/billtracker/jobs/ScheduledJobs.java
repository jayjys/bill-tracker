package com.billtracker.jobs;

import com.billtracker.analytics.anomaly.AnomalyService;
import com.billtracker.analytics.weather.WeatherService;
import com.billtracker.bill.BillRepository;
import com.billtracker.bill.BillStatus;
import com.billtracker.household.Household;
import com.billtracker.household.HouseholdRepository;
import com.billtracker.ingest.BillIngestionService;
import com.billtracker.parsing.BillParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Background work. Times are configurable; on a multi-instance deploy, add a lock
 * (e.g. ShedLock) so each job runs once.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "billtracker.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {

    public static final String WEATHER = "weather-sync";
    public static final String ANALYTICS = "anomaly-scan";
    public static final String REPARSE = "reparse-stale-bills";

    private final JobRunner runner;
    private final HouseholdRepository households;
    private final WeatherService weather;
    private final AnomalyService anomalies;
    private final BillRepository bills;
    private final BillIngestionService ingestion;

    public ScheduledJobs(JobRunner runner, HouseholdRepository households, WeatherService weather,
                         AnomalyService anomalies, BillRepository bills, BillIngestionService ingestion) {
        this.runner = runner;
        this.households = households;
        this.weather = weather;
        this.anomalies = anomalies;
        this.bills = bills;
        this.ingestion = ingestion;
    }

    /** Catch up after a deploy or downtime without blocking startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Thread.ofVirtual().name("startup-jobs").start(() -> {
            reparseStaleBills();
            syncWeather();
            scanForAnomalies();
        });
    }

    /** Pulls new daily temperatures for every household that has set a location. */
    @Scheduled(cron = "${billtracker.jobs.weather-cron:0 0 3 * * *}")
    public JobRun syncWeather() {
        return runner.run(WEATHER, households.findAllWithLocation(), weather::sync);
    }

    /** Re-checks recent bills against fresh weather; also raises due-soon reminders. */
    @Scheduled(cron = "${billtracker.jobs.analytics-cron:0 30 3 * * *}")
    public JobRun scanForAnomalies() {
        List<Household> all = households.findAll();
        return runner.run(ANALYTICS, all, anomalies::scan);
    }

    /**
     * When the parser improves (VERSION bump), bills it previously couldn't read are
     * re-read from their stored text, so fixes reach old bills without re-uploading.
     */
    @Scheduled(cron = "${billtracker.jobs.reparse-cron:0 0 4 * * SUN}")
    public JobRun reparseStaleBills() {
        return runner.run(REPARSE, bills.findStaleParses(BillParser.VERSION, BillStatus.NEEDS_REVIEW),
                bill -> ingestion.reparse(bill) ? 1 : 0);
    }
}
