package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** One vote (or applied choice) on a variation point inside a particular Watch Space. */
@Entity
@Table(name = "room_variations",
        uniqueConstraints = @UniqueConstraint(name = "uk_rv_ws_event", columnNames = {"watch_space_id", "event_id"}))
public class RoomVariation {
    public enum Status { OPEN, CLOSED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "watch_space_id", nullable = false)
    public WatchSpace watchSpace;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    public TimelineEvent event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    public Status status = Status.OPEN;

    @Column(name = "opened_at", nullable = false)
    public Instant openedAt = Instant.now();

    @Column(name = "closes_at", nullable = false)
    public Instant closesAt;

    @Column(name = "winning_option_key", length = 32)
    public String winningOptionKey;
}
