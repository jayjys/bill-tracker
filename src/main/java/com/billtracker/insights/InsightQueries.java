package com.billtracker.insights;

import com.billtracker.household.Household;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Plain SQL answers to questions about a household's bills. Each query lives in
 * {@code resources/sql/insights/*.sql} with a {@code -- title:} and {@code -- question:}
 * header, so the SQL is reviewable on its own and shown verbatim in the UI.
 * Every query is parameterised by {@code :householdId}.
 */
@Component
public class InsightQueries {

    public record Query(String id, String title, String question, String sql) {
    }

    public record Result(Query query, List<String> columns, List<Map<String, Object>> rows, String error) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final List<Query> queries;

    public InsightQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.queries = load();
    }

    public List<Query> all() {
        return queries;
    }

    /** Runs every query for the household. One failing query doesn't break the page. */
    @Transactional(readOnly = true)
    public List<Result> run(Household household, LocalDate today) {
        MapSqlParameterSource params = params(household.getId(), today);
        List<Result> results = new ArrayList<>();
        for (Query q : queries) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), params);
                List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.getFirst().keySet());
                results.add(new Result(q, columns, rows, null));
            } catch (RuntimeException e) {
                results.add(new Result(q, List.of(), List.of(), NestedExceptionUtils.getMostSpecificCause(e).getMessage()));
            }
        }
        return results;
    }

    static MapSqlParameterSource params(long householdId, LocalDate today) {
        return new MapSqlParameterSource()
                .addValue("householdId", householdId)
                .addValue("today", today)
                .addValue("since", today.minusMonths(12).withDayOfMonth(1))
                .addValue("longSince", today.minusYears(3).withDayOfMonth(1))
                .addValue("recentSince", today.minusMonths(3).withDayOfMonth(1))
                .addValue("dueFrom", today.minusDays(7))
                .addValue("dueTo", today.plusDays(30));
    }

    private static List<Query> load() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:sql/insights/*.sql");
            return Arrays.stream(files)
                    .sorted(Comparator.comparing(Resource::getFilename))
                    .map(InsightQueries::parse)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Query parse(Resource file) {
        try {
            String sql = file.getContentAsString(StandardCharsets.UTF_8).strip();
            String title = header(sql, "title");
            String question = header(sql, "question");
            String body = sql.lines().filter(l -> !l.startsWith("-- title:") && !l.startsWith("-- question:"))
                    .reduce((a, b) -> a + "\n" + b).orElse("").strip();
            String id = file.getFilename().replaceFirst("^\\d+_", "").replace(".sql", "");
            return new Query(id, title, question, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String header(String sql, String key) {
        return sql.lines().filter(l -> l.startsWith("-- " + key + ":"))
                .map(l -> l.substring(("-- " + key + ":").length()).strip())
                .findFirst().orElse(key);
    }
}
