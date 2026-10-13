package com.nsure.mipas.control;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationalControlHistoryRepository extends JpaRepository<OperationalControlHistory, Long> {

    List<OperationalControlHistory> findByControlKeyOrderByChangedAtDesc(String controlKey);
}
