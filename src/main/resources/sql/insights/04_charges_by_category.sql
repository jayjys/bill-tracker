-- title: Where the money goes
-- question: Of what I pay, how much is the energy/water itself versus delivery, fees and taxes? (From parsed line items.)
SELECT c.category,
       COUNT(*)                                                       AS line_items,
       SUM(c.amount)                                                  AS spend,
       ROUND(100.0 * SUM(c.amount) / SUM(SUM(c.amount)) OVER (), 1)   AS pct_of_itemised
FROM bill_charge c
JOIN bill b ON b.id = c.bill_id
WHERE b.household_id = :householdId
  AND COALESCE(b.period_end, b.due_date) >= :since
GROUP BY c.category
ORDER BY spend DESC
