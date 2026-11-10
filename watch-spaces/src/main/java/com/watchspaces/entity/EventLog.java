package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only log of room events. Session analytics are computed from (and reconcile with) this table. */
@Entity
@Table(name = "event_log", indexes = @Index(name = "idx_evlog_ws_type", columnList = "watch_space_id, event_type"))
public class EventLog {
    public enum LogType { JOIN, LEAVE, CHAT, AI_QUESTION, TRIVIA, PLAYBACK, VOTE_OPEN, VOTE_CAST, VOTE_APPLIED, HOST_CHANGE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "watch_space_id", nullable = false)
    public WatchSpace watchSpace;

    @ManyToOne
    @JoinColumn(name = "user_id")
    public User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    public LogType type;

    /** Short, non-sensitive detail (never chat or question text). */
    @Column(length = 300)
    public String detail;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
