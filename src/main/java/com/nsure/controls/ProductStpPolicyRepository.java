package com.nsure.controls;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductStpPolicyRepository extends JpaRepository<ProductStpPolicy, Long> {

    Optional<ProductStpPolicy> findByProductId(Long productId);
}
