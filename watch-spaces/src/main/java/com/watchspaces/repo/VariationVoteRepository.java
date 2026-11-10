package com.watchspaces.repo;

import com.watchspaces.entity.VariationVote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariationVoteRepository extends JpaRepository<VariationVote, Long> {
    Optional<VariationVote> findByRoomVariation_IdAndUser_Id(Long roomVariationId, Long userId);

    List<VariationVote> findByRoomVariation_Id(Long roomVariationId);
}
