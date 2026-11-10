package com.watchspaces.repo;

import com.watchspaces.entity.RoomVariation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomVariationRepository extends JpaRepository<RoomVariation, Long> {
    Optional<RoomVariation> findByWatchSpace_IdAndEvent_Id(Long watchSpaceId, Long eventId);

    List<RoomVariation> findByWatchSpace_Id(Long watchSpaceId);

    List<RoomVariation> findByStatus(RoomVariation.Status status);

    boolean existsByEvent_Title_Id(Long titleId);
}
