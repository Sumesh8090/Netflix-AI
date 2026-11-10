package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Entities use public fields to stay compact; they are never serialised directly (DTO records are used). */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 190)
    public String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    public String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    public String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Role role = Role.VIEWER;

    @Column(name = "subtitle_locale", nullable = false, length = 16)
    public String subtitleLocale = "en";

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
