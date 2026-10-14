package com.billtracker.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/** One execution of a background job, kept for the status page and debugging. */
@Entity
@Table(name = "job_run")
public class JobRun {

    public enum Status { RUNNING, OK, PARTIAL, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false)
    private String jobName;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    private Integer items;

    private String message;

    protected JobRun() {
    }

    public JobRun(String jobName) {
        this.jobName = jobName;
        this.startedAt = Instant.now();
        this.status = Status.RUNNING;
    }

    public void finish(Status status, int items, String message) {
        this.finishedAt = Instant.now();
        this.status = status;
        this.items = items;
        this.message = message == null || message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    public Long getId() {
        return id;
    }

    public String getJobName() {
        return jobName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getItems() {
        return items;
    }

    public String getMessage() {
        return message;
    }

    public Long getDurationMillis() {
        return finishedAt == null ? null : Duration.between(startedAt, finishedAt).toMillis();
    }
}
