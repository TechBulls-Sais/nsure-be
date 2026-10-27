package com.nsure.audit.repository;

import com.nsure.audit.domain.CommandSource;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommandSourceRepository extends JpaRepository<CommandSource, Long> {

    Optional<CommandSource> findByActionNameAndEntityNameAndIdempotencyKey(
            String actionName, String entityName, String idempotencyKey);

    @Query("""
            select c from CommandSource c
            where (:actionName is null or c.actionName = :actionName)
              and (:entityName is null or c.entityName = :entityName)
              and (:resourceId is null or c.resourceId = :resourceId)
              and (:makerId is null or c.makerId = :makerId)
              and (:status is null or c.status = :status)
              and (:from is null or c.madeOn >= :from)
              and (:to is null or c.madeOn < :to)
              and (:headOffice = true or c.officeId in (
                    select o.id from Office o where o.hierarchy like concat(:hierarchy, '%')))
            """)
    Page<CommandSource> search(
            @Param("actionName") String actionName,
            @Param("entityName") String entityName,
            @Param("resourceId") Long resourceId,
            @Param("makerId") Long makerId,
            @Param("status") String status,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("headOffice") boolean headOffice,
            @Param("hierarchy") String hierarchy,
            Pageable pageable);
}
