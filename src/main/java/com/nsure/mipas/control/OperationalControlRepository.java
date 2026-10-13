package com.nsure.mipas.control;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationalControlRepository extends JpaRepository<OperationalControl, Long> {

    Optional<OperationalControl> findByControlKey(String controlKey);
}
