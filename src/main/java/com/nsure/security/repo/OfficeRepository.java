package com.nsure.security.repo;

import com.nsure.security.domain.Office;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfficeRepository extends JpaRepository<Office, Long> {

    @Query("""
            select office from Office office
            where :headOffice = true
               or office.hierarchy like concat(:hierarchy, '%')
            order by office.hierarchy
            """)
    List<Office> findVisible(@Param("headOffice") boolean headOffice, @Param("hierarchy") String hierarchy);
}
