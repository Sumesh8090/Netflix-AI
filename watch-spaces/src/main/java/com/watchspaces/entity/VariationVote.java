package com.watchspaces.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "variation_votes",
        uniqueConstraints = @UniqueConstraint(name = "uk_vote_rv_user", columnNames = {"room_variation_id", "user_id"}))
public class VariationVote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "room_variation_id", nullable = false)
    public RoomVariation roomVariation;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @Column(name = "option_key", nullable = false, length = 32)
    public String optionKey;
}
