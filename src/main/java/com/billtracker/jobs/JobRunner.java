package com.billtracker.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Runs a job over a list of items (households, bills), isolating failures: one bad item
 * is logged and counted but doesn't stop the rest. Every run is recorded in {@code job_run}.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRunRepository runs;

    public JobRunner(JobRunRepository runs) {
        this.runs = runs;
    }

    /**
     * @param work returns how many things it changed for the item (rows saved, alerts raised...)
     */
    public <T> JobRun run(String name, List<T> items, Function<T, Integer> work) {
        JobRun run = runs.save(new JobRun(name));
        int changed = 0;
        List<String> errors = new ArrayList<>();
        for (T item : items) {
            try {
                changed += work.apply(item);
            } catch (RuntimeException e) {
                errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                log.warn("Job {} failed for {}: {}", name, item, e.toString());
            }
        }
        JobRun.Status status = errors.isEmpty() ? JobRun.Status.OK
                : errors.size() == items.size() ? JobRun.Status.FAILED : JobRun.Status.PARTIAL;
        String message = items.size() + " processed" + (errors.isEmpty() ? "" : ", " + errors.size() + " failed: "
                + String.join("; ", errors.subList(0, Math.min(3, errors.size()))));
        run.finish(status, changed, message);
        log.info("Job {} finished: {} ({} changed)", name, status, changed);
        return runs.save(run);
    }
}
