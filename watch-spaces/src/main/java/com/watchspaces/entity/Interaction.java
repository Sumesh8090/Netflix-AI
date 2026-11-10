package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Viewing/rating record. This table feeds the recommendation engine. */
@Entity
@Table(name = "interactions", indexes = {
        @Index(name = "idx_inter_user", columnList = "user_id"),
        @Index(name = "idx_inter_title", columnList = "title_id")})
public class Interaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "title_id", nullable = false)
    public Title title;

    @Column(name = "watched_seconds", nullable = false)
    public int watchedSeconds;

    @Column(nullable = false)
    public boolean completed;

    /** 1..5, null when the viewer did not rate. */
    @Column
    public Integer rating;

    /** Comma separated user ids this viewer watched with (co-watch signal). */
    @Column(name = "co_watched_with", length = 1000)
    public String coWatchedWith;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
