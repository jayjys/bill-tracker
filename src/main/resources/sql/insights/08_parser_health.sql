-- title: Parser health by provider
-- question: How reliably is each provider's bill read automatically? A drop for one provider usually means it changed its bill layout.
SELECT a.provider,
       COUNT(*)                                              AS bills,
       COUNT(*) FILTER (WHERE b.status = 'PARSED')           AS read_cleanly,
       COUNT(*) FILTER (WHERE b.status = 'NEEDS_REVIEW')     AS needs_review,
       COUNT(*) FILTER (WHERE b.parse_warnings IS NOT NULL)  AS with_warnings,
       ROUND(AVG(b.parse_confidence), 2)                     AS avg_confidence,
       MAX(b.created_at)                                     AS last_received
FROM bill b
JOIN utility_account a ON a.id = b.utility_account_id
WHERE b.household_id = :householdId
  AND b.source <> 'MANUAL'
GROUP BY a.provider
ORDER BY bills DESC
