package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_messages",
        indexes = @Index(name = "idx_chat_ws_seq", columnList = "watch_space_id, seq"),
        uniqueConstraints = @UniqueConstraint(name = "uk_chat_uuid", columnNames = "message_uuid"))
public class ChatMessage {
    public enum MsgType { CHAT, AI_ANSWER, SYSTEM }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "message_uuid", nullable = false, length = 36)
    public String messageUuid;

    @ManyToOne(optional = false)
    @JoinColumn(name = "watch_space_id", nullable = false)
    public WatchSpace watchSpace;

    /** Author (CHAT), asker (AI_ANSWER) or null (SYSTEM). */
    @ManyToOne
    @JoinColumn(name = "user_id")
    public User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "msg_type", nullable = false, length = 16)
    public MsgType msgType;

    @Column(nullable = false, length = 4000)
    public String body;

    /** Per-room delivery order, assigned under the room's broadcast lock. */
    @Column(nullable = false)
    public long seq;

    @Column(name = "ts_seconds", nullable = false)
    public double tsSeconds;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
