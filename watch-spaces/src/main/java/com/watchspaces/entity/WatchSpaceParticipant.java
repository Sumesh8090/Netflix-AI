package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "watch_space_participants",
        uniqueConstraints = @UniqueConstraint(name = "uk_wsp_ws_user", columnNames = {"watch_space_id", "user_id"}))
public class WatchSpaceParticipant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "watch_space_id", nullable = false)
    public WatchSpace watchSpace;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @Column(name = "joined_at", nullable = false)
    public Instant joinedAt = Instant.now();

    @Column(name = "left_at")
    public Instant leftAt;
}
