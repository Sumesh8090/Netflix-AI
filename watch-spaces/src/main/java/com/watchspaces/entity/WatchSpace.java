package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "watch_spaces",
        uniqueConstraints = @UniqueConstraint(name = "uk_ws_invite", columnNames = "invite_code"),
        indexes = {@Index(name = "idx_ws_host", columnList = "host_user_id"), @Index(name = "idx_ws_status", columnList = "status")})
public class WatchSpace {
    public enum Status { SCHEDULED, LIVE, ENDED }

    public enum Privacy { PUBLIC_LINK, INVITE_ONLY }

    public enum Verbosity { MINIMAL, NORMAL, CHATTY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "invite_code", nullable = false, length = 16)
    public String inviteCode;

    @Column(nullable = false, length = 120)
    public String name;

    @ManyToOne(optional = false)
    @JoinColumn(name = "title_id", nullable = false)
    public Title title;

    @ManyToOne(optional = false)
    @JoinColumn(name = "host_user_id", nullable = false)
    public User host;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Privacy privacy = Privacy.INVITE_ONLY;

    @Column(name = "max_participants", nullable = false)
    public int maxParticipants = 25;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_verbosity", nullable = false, length = 16)
    public Verbosity aiVerbosity = Verbosity.NORMAL;

    @Column(name = "voting_enabled", nullable = false)
    public boolean votingEnabled = false;

    @Column(name = "trivia_enabled", nullable = false)
    public boolean triviaEnabled = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "ended_at")
    public Instant endedAt;

    // ---- analytics snapshot, filled in when the session ends (computed from the event log) ----
    @Column(name = "snap_peak_participants")
    public Integer snapPeakParticipants;
    @Column(name = "snap_ai_questions")
    public Integer snapAiQuestions;
    @Column(name = "snap_trivia_cards")
    public Integer snapTriviaCards;
    @Column(name = "snap_chat_messages")
    public Integer snapChatMessages;
    @Column(name = "snap_duration_seconds")
    public Long snapDurationSeconds;
}
