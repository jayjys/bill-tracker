-- title: Roommate split
-- question: What is each member's equal share of the last three months, and who added which bills?
WITH monthly AS (
    SELECT CAST(DATE_TRUNC('MONTH', COALESCE(b.period_end, b.due_date)) AS DATE) AS month_start,
           SUM(b.amount) AS total
    FROM bill b
    WHERE b.household_id = :householdId AND b.amount IS NOT NULL
      AND COALESCE(b.period_end, b.due_date) >= :recentSince
    GROUP BY CAST(DATE_TRUNC('MONTH', COALESCE(b.period_end, b.due_date)) AS DATE)
),
members AS (
    SELECT u.id, COALESCE(u.display_name, u.email) AS name
    FROM app_user u
    WHERE u.household_id = :householdId
),
added AS (
    SELECT b.user_id,
           CAST(DATE_TRUNC('MONTH', COALESCE(b.period_end, b.due_date)) AS DATE) AS month_start,
           COUNT(*) AS bills_added
    FROM bill b
    WHERE b.household_id = :householdId
    GROUP BY b.user_id, CAST(DATE_TRUNC('MONTH', COALESCE(b.period_end, b.due_date)) AS DATE)
)
SELECT m.month_start,
       mem.name                                                       AS member,
       m.total                                                        AS household_total,
       ROUND(m.total / (SELECT COUNT(*) FROM members), 2)             AS equal_share,
       COALESCE(a.bills_added, 0)                                     AS bills_added
FROM monthly m
CROSS JOIN members mem
LEFT JOIN added a ON a.user_id = mem.id AND a.month_start = m.month_start
ORDER BY m.month_start DESC, mem.name
