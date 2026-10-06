package com.fleettwin.recommendation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    Optional<Recommendation> findByVehicleIdAndComponentAndStatusIn(
            Long vehicleId, String component, Collection<Recommendation.State> statuses);

    Optional<Recommendation> findFirstByVehicleIdAndComponentAndStatusOrderByUpdatedAtDesc(
            Long vehicleId, String component, Recommendation.State status);

    List<Recommendation> findByStatusIn(Collection<Recommendation.State> statuses);

    long countByVehicleIdAndStatus(Long vehicleId, Recommendation.State status);
}
