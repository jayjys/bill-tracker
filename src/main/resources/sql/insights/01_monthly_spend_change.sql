-- title: Month-over-month spend by utility
-- question: How much did each utility cost per month, and how did that change from the month before?
WITH monthly AS (
    SELECT a.utility_type,
           CAST(DATE_TRUNC('MONTH', COALESCE(b.period_end, b.due_date)) AS DATE) AS month_start,
           SUM(b.amount) AS spend
    FROM bill b
    JOIN utility_account a ON a.id = b.utility_account_id
    WHERE b.household_id = :householdId
      AND b.amount IS NOT NULL
      AND COALESCE(b.period_end, b.due_date) >= :since
    GROUP BY a.utility_type, CAST(DATE_TRUNC('MONTH', COALESCE(b.period_end, b.due_date)) AS DATE)
)
SELECT month_start,
       utility_type,
       spend,
       spend - LAG(spend) OVER (PARTITION BY utility_type ORDER BY month_start) AS change_amount,
       ROUND(100.0 * (spend - LAG(spend) OVER (PARTITION BY utility_type ORDER BY month_start))
             / NULLIF(LAG(spend) OVER (PARTITION BY utility_type ORDER BY month_start), 0), 1) AS pct_change
FROM monthly
ORDER BY month_start DESC, spend DESC
