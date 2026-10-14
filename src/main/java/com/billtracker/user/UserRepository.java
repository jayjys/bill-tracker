package com.billtracker.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    Optional<AppUser> findByInboundToken(String inboundToken);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findByHouseholdIdOrderByCreatedAtAsc(Long householdId);

    long countByHouseholdId(Long householdId);
}
