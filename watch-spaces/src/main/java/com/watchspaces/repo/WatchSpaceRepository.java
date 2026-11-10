package com.watchspaces.repo;

import com.watchspaces.entity.WatchSpace;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchSpaceRepository extends JpaRepository<WatchSpace, Long> {
    Optional<WatchSpace> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);

    @Query("select distinct w from WatchSpace w where w.host.id = :uid or w.id in "
            + "(select p.watchSpace.id from WatchSpaceParticipant p where p.user.id = :uid) order by w.createdAt desc")
    List<WatchSpace> findForUser(@Param("uid") Long userId);
}
