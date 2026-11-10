package com.watchspaces.repo;

import com.watchspaces.entity.TimelineEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, Long> {

    List<TimelineEvent> findByTitle_IdOrderByTsSecondsAscIdAsc(Long titleId);

    /** Events whose [ts, endTs] window overlaps [from, to]. Served by idx_timeline_title_ts. */
    @Query("select e from TimelineEvent e where e.title.id = :titleId and e.tsSeconds <= :to "
            + "and coalesce(e.endTsSeconds, e.tsSeconds) >= :from order by e.tsSeconds asc, e.id asc")
    List<TimelineEvent> findInRange(@Param("titleId") Long titleId, @Param("from") int from, @Param("to") int to);

    long countByTitle_Id(Long titleId);
}
