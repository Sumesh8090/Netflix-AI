package com.watchspaces.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "titles")
public class Title {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(name = "duration_seconds", nullable = false)
    public int durationSeconds;

    /** Comma separated genre names, e.g. "Crime,Thriller". */
    @Column(nullable = false, length = 200)
    public String genres = "";

    @Column(name = "video_asset_url", length = 500)
    public String videoAssetUrl;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    public Set<String> genreSet() {
        if (genres == null || genres.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(genres.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
