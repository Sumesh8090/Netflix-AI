package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", indexes = @Index(name = "idx_refresh_hash", columnList = "token_hash", unique = true))
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    /** SHA-256 of the opaque token; the raw token is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64)
    public String tokenHash;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(nullable = false)
    public boolean revoked = false;
}
