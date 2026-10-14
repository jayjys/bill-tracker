package com.billtracker.household;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface HouseholdRepository extends JpaRepository<Household, Long> {

    Optional<Household> findByInviteCode(String inviteCode);

    @Query("select h from Household h where h.latitude is not null and h.longitude is not null")
    List<Household> findAllWithLocation();
}
