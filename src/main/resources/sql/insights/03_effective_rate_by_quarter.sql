-- title: Effective price per unit, by quarter
-- question: Is the price I pay per kWh / therm / gallon going up? (Usage-weighted, so a big month counts more than a small one.)
SELECT a.provider,
       a.utility_type,
       b.usage_unit                                                       AS unit,
       EXTRACT(YEAR FROM b.period_end)                                    AS yr,
       FLOOR((EXTRACT(MONTH FROM b.period_end) - 1) / 3) + 1              AS qtr,
       SUM(b.usage_quantity)                                              AS total_usage,
       SUM(b.amount)                                                      AS spend,
       ROUND(SUM(b.amount) / NULLIF(SUM(b.usage_quantity), 0), 4)         AS price_per_unit
FROM bill b
JOIN utility_account a ON a.id = b.utility_account_id
WHERE b.household_id = :householdId
  AND b.usage_quantity > 0
  AND b.amount IS NOT NULL
  AND b.period_end >= :longSince
GROUP BY a.provider, a.utility_type, b.usage_unit,
         EXTRACT(YEAR FROM b.period_end), FLOOR((EXTRACT(MONTH FROM b.period_end) - 1) / 3) + 1
ORDER BY a.utility_type, yr DESC, qtr DESC
