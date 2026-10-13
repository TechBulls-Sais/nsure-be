package com.nsure.mipas.capability;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MipasCapabilityRepository extends JpaRepository<MipasCapability, Long> {

    List<MipasCapability> findAllByOrderByGroupingAscCodeAsc();
}
