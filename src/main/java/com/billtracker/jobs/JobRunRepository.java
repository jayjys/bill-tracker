package com.billtracker.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    List<JobRun> findTop25ByOrderByStartedAtDesc();
}
