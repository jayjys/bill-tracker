-- title: Normalised cost: per day and per person
-- question: Bills cover different numbers of days and households have different sizes. What does each utility really cost per day and per person?
SELECT a.utility_type,
       COUNT(*)                                                         AS bills,
       SUM(b.amount)                                                    AS spend,
       SUM(COALESCE(b.service_days, 30))                                AS days_billed,
       ROUND(SUM(b.amount) / SUM(COALESCE(b.service_days, 30)), 2)      AS cost_per_day,
       h.occupants                                                      AS people,
       ROUND(SUM(b.amount) / SUM(COALESCE(b.service_days, 30)) / h.occupants, 2)          AS per_person_per_day,
       ROUND(SUM(b.amount) / SUM(COALESCE(b.service_days, 30)) * 30.44 / h.occupants, 2)  AS per_person_per_month
FROM bill b
JOIN utility_account a ON a.id = b.utility_account_id
JOIN household h ON h.id = b.household_id
WHERE b.household_id = :householdId
  AND b.amount IS NOT NULL
  AND COALESCE(b.period_end, b.due_date) >= :since
GROUP BY a.utility_type, h.occupants
ORDER BY spend DESC
