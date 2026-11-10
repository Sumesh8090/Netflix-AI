package com.watchspaces.repo;

import com.watchspaces.entity.ChatMessage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByWatchSpace_IdOrderBySeqDesc(Long watchSpaceId, Pageable page);

    List<ChatMessage> findByWatchSpace_IdAndSeqLessThanOrderBySeqDesc(Long watchSpaceId, long seq, Pageable page);

    @Query("select coalesce(max(c.seq), 0) from ChatMessage c where c.watchSpace.id = :id")
    long maxSeq(@Param("id") Long watchSpaceId);

    long countByWatchSpace_IdAndMsgType(Long watchSpaceId, ChatMessage.MsgType type);
}
