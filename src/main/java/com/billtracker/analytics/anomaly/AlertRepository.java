package com.billtracker.analytics.anomaly;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByHouseholdIdAndDismissedAtIsNullOrderByCreatedAtDesc(Long householdId);

    /** Open alerts, most severe first, then newest. */
    default List<Alert> openAlerts(Long householdId) {
        return findByHouseholdIdAndDismissedAtIsNullOrderByCreatedAtDesc(householdId).stream()
                .sorted(java.util.Comparator.comparing(Alert::getSeverity).reversed()
                        .thenComparing(Alert::getCreatedAt, java.util.Comparator.reverseOrder()))
                .toList();
    }

    Optional<Alert> findByIdAndHouseholdId(Long id, Long householdId);

    boolean existsByBill_IdAndKind(Long billId, AlertKind kind);
}
