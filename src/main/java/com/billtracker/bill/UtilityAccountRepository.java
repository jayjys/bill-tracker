package com.billtracker.bill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UtilityAccountRepository extends JpaRepository<UtilityAccount, Long> {

    Optional<UtilityAccount> findByHouseholdIdAndProviderAndUtilityTypeAndAccountLast4(
            Long householdId, String provider, UtilityType utilityType, String accountLast4);

    List<UtilityAccount> findByHouseholdId(Long householdId);
}
