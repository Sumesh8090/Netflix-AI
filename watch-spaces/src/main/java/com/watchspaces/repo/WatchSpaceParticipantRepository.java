package com.watchspaces.repo;

import com.watchspaces.entity.WatchSpaceParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchSpaceParticipantRepository extends JpaRepository<WatchSpaceParticipant, Long> {
    Optional<WatchSpaceParticipant> findByWatchSpace_IdAndUser_Id(Long watchSpaceId, Long userId);

    List<WatchSpaceParticipant> findByWatchSpace_Id(Long watchSpaceId);

    long countByWatchSpace_Id(Long watchSpaceId);
}
