-- title: Due soon
-- question: Which bills are due in the next 30 days (or came due in the last week)?
SELECT b.due_date,
       a.provider,
       a.utility_type,
       b.amount,
       CASE WHEN b.due_date < :today THEN 'due date passed' ELSE 'upcoming' END AS status
FROM bill b
JOIN utility_account a ON a.id = b.utility_account_id
WHERE b.household_id = :householdId
  AND b.due_date BETWEEN :dueFrom AND :dueTo
ORDER BY b.due_date
