package com.nsure.actuary;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActuaryRepository extends JpaRepository<Actuary, Long> {

    Optional<Actuary> findByRegistrationNumber(String registrationNumber);

    Optional<Actuary> findByUserId(Long userId);
}
