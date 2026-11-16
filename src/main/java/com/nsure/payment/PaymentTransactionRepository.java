package com.nsure.payment;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** Spec 13 S-11: concurrent triggers queue on this row lock instead of racing. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.providerToken = :token")
    Optional<PaymentTransaction> findByProviderTokenForUpdate(@Param("token") String token);

    @Query("select t from PaymentTransaction t where t.status in :statuses and t.createdAt < :before order by t.createdAt")
    List<PaymentTransaction> findUnsettled(@Param("statuses") Collection<String> statuses,
            @Param("before") LocalDateTime before, Pageable page);

    List<PaymentTransaction> findByPolicyRefAndPeriodStartAndPurposeOrderByIdDesc(
            String policyRef, LocalDate periodStart, String purpose);

    List<PaymentTransaction> findAllByOrderByIdDesc(Pageable page);

    Optional<PaymentTransaction> findByProviderReference(String providerReference);
}
