-- title: This year vs. last year
-- question: For each month, am I spending more or less than the same month a year earlier?
WITH dated AS (
    SELECT COALESCE(b.period_end, b.due_date) AS d, b.amount
    FROM bill b
    WHERE b.household_id = :householdId AND b.amount IS NOT NULL
),
monthly AS (
    SELECT EXTRACT(YEAR FROM d) AS yr, EXTRACT(MONTH FROM d) AS mo, SUM(amount) AS spend
    FROM dated
    WHERE d IS NOT NULL
    GROUP BY EXTRACT(YEAR FROM d), EXTRACT(MONTH FROM d)
)
SELECT cur.yr                                                         AS yr,
       cur.mo AS mo,
       cur.spend                                                      AS spend,
       prev.spend                                                     AS same_month_last_year,
       ROUND(100.0 * (cur.spend - prev.spend) / NULLIF(prev.spend, 0), 1) AS pct_change
FROM monthly cur
LEFT JOIN monthly prev ON prev.yr = cur.yr - 1 AND prev.mo = cur.mo
WHERE prev.spend IS NOT NULL
ORDER BY cur.yr DESC, cur.mo DESC
