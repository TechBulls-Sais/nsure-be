package com.nsure.security.repository;

import com.nsure.security.domain.Office;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfficeRepository extends JpaRepository<Office, Long> {

    @Query("""
            select o from Office o
            where o.hierarchy like concat(:hierarchy, '%')
            order by o.hierarchy
            """)
    List<Office> findInHierarchy(@Param("hierarchy") String hierarchy);
}
