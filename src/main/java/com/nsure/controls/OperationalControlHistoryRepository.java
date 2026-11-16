package com.nsure.controls;

import java.util.List;
import org.springframework.data.repository.Repository;

/** Append-only: save and read, nothing else (spec 12 C-5). */
public interface OperationalControlHistoryRepository extends Repository<OperationalControlHistory, Long> {

    OperationalControlHistory save(OperationalControlHistory row);

    List<OperationalControlHistory> findByControlKeyOrderByChangedAtDescIdDesc(String controlKey);
}
