package com.watchspaces.repo;

import com.watchspaces.entity.EventLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventLogRepository extends JpaRepository<EventLog, Long> {
    List<EventLog> findByWatchSpace_IdOrderByIdAsc(Long watchSpaceId);

    long countByWatchSpace_IdAndType(Long watchSpaceId, EventLog.LogType type);
}
