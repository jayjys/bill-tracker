package com.billtracker.bill;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BillRepository extends JpaRepository<Bill, Long> {

    /** Every bill visible to a household; uploader is fetched for the "added by" column. */
    @EntityGraph(attributePaths = {"uploadedBy", "account"})
    List<Bill> findByHouseholdIdOrderByCreatedAtDesc(Long householdId);

    @EntityGraph(attributePaths = {"charges", "uploadedBy", "household", "account"})
    Optional<Bill> findWithChargesByIdAndHouseholdId(Long id, Long householdId);

    Optional<Bill> findByIdAndHouseholdId(Long id, Long householdId);

    boolean existsByHouseholdIdAndContentSha256(Long householdId, String contentSha256);

    long countByHouseholdIdAndStatus(Long householdId, BillStatus status);

    List<Bill> findByAccountIdOrderByPeriodEndAsc(Long accountId);

    /** Bills whose source text was read by an older parser and still need attention. */
    @Query("select b from Bill b where b.parserVersion < :version and b.rawText is not null and b.status = :status")
    List<Bill> findStaleParses(int version, BillStatus status);

    /** Earlier machine-parsed bills from the same account, to spot a provider changing its layout. */
    @Query("""
            select count(b) from Bill b
            where b.account.id = :accountId and b.id <> :excludeId
              and b.source <> com.billtracker.bill.BillSource.MANUAL and b.parseConfidence >= 0.8
            """)
    long countWellParsedFromAccount(Long accountId, Long excludeId);
}
