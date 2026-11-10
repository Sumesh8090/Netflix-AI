package com.watchspaces.service;

import com.watchspaces.core.WriteBuffer;
import com.watchspaces.entity.ChatMessage;
import com.watchspaces.entity.EventLog;
import com.watchspaces.repo.ChatMessageRepository;
import com.watchspaces.repo.EventLogRepository;
import com.watchspaces.repo.UserRepository;
import com.watchspaces.repo.WatchSpaceRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chat messages and the event log are written in batches (every 2 s or 20 rows, whichever first) so the database
 * never sees one write per WebSocket message. Playback heartbeats are never persisted at all.
 */
@Component
public class PersistenceBuffers {
    private static final Logger log = LoggerFactory.getLogger(PersistenceBuffers.class);

    private final WriteBuffer<ChatMessage> chat;
    private final WriteBuffer<EventLog> events;
    private final WatchSpaceRepository watchSpaces;
    private final UserRepository users;

    public PersistenceBuffers(ChatMessageRepository chatRepo, EventLogRepository logRepo,
                              WatchSpaceRepository watchSpaces, UserRepository users) {
        this.watchSpaces = watchSpaces;
        this.users = users;
        this.chat = new WriteBuffer<>(20, 2000, batch -> {
            try {
                chatRepo.saveAll(batch);
            } catch (RuntimeException e) {
                log.error("persist.chat.failed batch={} error={}", batch.size(), e.toString());
            }
        }, System::currentTimeMillis);
        this.events = new WriteBuffer<>(20, 2000, batch -> {
            try {
                logRepo.saveAll(batch);
            } catch (RuntimeException e) {
                log.error("persist.eventlog.failed batch={} error={}", batch.size(), e.toString());
            }
        }, System::currentTimeMillis);
    }

    public void addChat(long watchSpaceId, Long userId, ChatMessage.MsgType type, String uuid, String body, long seq,
                        double tsSeconds) {
        ChatMessage m = new ChatMessage();
        m.messageUuid = uuid;
        m.watchSpace = watchSpaces.getReferenceById(watchSpaceId);
        m.user = userId == null ? null : users.getReferenceById(userId);
        m.msgType = type;
        m.body = body;
        m.seq = seq;
        m.tsSeconds = tsSeconds;
        m.createdAt = Instant.now();
        chat.add(m);
    }

    public void log(long watchSpaceId, Long userId, EventLog.LogType type, String detail) {
        EventLog e = new EventLog();
        e.watchSpace = watchSpaces.getReferenceById(watchSpaceId);
        e.user = userId == null ? null : users.getReferenceById(userId);
        e.type = type;
        e.detail = detail == null ? null : (detail.length() > 300 ? detail.substring(0, 300) : detail);
        e.createdAt = Instant.now();
        events.add(e);
    }

    /** Readers (history, analytics, session end) call this first so they never miss buffered rows. */
    public void flushAll() {
        chat.flush();
        events.flush();
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        chat.flushIfDue();
        events.flushIfDue();
    }

    @PreDestroy
    public void shutdown() {
        flushAll();
    }
}
