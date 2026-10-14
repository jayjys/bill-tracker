package com.billtracker;

import com.billtracker.bill.BillRepository;
import com.billtracker.bill.BillSource;
import com.billtracker.ingest.BillIngestionService;
import com.billtracker.insights.InsightQueries;
import com.billtracker.trends.TrendService;
import com.billtracker.user.AppUser;
import com.billtracker.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Flyway migrations and the ingest → trends path against real PostgreSQL.
 * Skipped automatically when Docker isn't available (it runs in CI).
 */
@SpringBootTest
@TestPropertySource(properties = "billtracker.jobs.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    UserService users;
    @Autowired
    BillIngestionService ingestion;
    @Autowired
    BillRepository bills;
    @Autowired
    TrendService trends;
    @Autowired
    InsightQueries insights;

    @Test
    void ingestsAndReportsAgainstPostgres() {
        AppUser user = users.register("pg@example.com", "a-long-password", null);

        var outcomes = ingestion.ingestFile(user, "pge.txt", "text/plain",
                TestFixtures.bytes("pge-electric.txt"), BillSource.UPLOAD);

        assertThat(outcomes).singleElement()
                .extracting(BillIngestionService.Outcome::result).isEqualTo(BillIngestionService.Result.CREATED);
        assertThat(bills.findByHouseholdIdOrderByCreatedAtDesc(user.getHousehold().getId())).hasSize(1);
        assertThat(trends.monthly(user.getHousehold(), 12).summary().latestMonthTotal()).isEqualByComparingTo("98.62");

        ingestion.ingestFile(user, "combined.txt", "text/plain", TestFixtures.bytes("pge-combined.txt"),
                BillSource.UPLOAD);
        // The insight SQL must run on real PostgreSQL, not just H2's compatibility mode.
        assertThat(insights.run(user.getHousehold(), LocalDate.of(2026, 9, 15)))
                .allSatisfy(r -> assertThat(r.error()).as(r.query().id()).isNull());
    }
}
