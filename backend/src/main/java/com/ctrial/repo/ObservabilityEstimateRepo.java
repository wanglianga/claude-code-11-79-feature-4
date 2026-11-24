package com.ctrial.repo;

import com.ctrial.model.ObservabilityEstimate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObservabilityEstimateRepo extends JpaRepository<ObservabilityEstimate, Long> {
    List<ObservabilityEstimate> findAllByOrderByCreatedAtDesc();
    List<ObservabilityEstimate> findByVersionGroupOrderByVersionAsc(String versionGroup);
    Optional<ObservabilityEstimate> findByCode(String code);
    Optional<ObservabilityEstimate> findFirstByVersionGroupOrderByVersionDesc(String versionGroup);
}
