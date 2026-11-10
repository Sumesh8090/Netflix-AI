package com.watchspaces.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "timeline_events", indexes = @Index(name = "idx_timeline_title_ts", columnList = "title_id, ts_seconds"))
public class TimelineEvent {
    public enum EventType { SCENE, CHARACTER, TRIVIA, GLOSSARY, VARIATION_POINT }

    public enum VariationKind { SUBTITLE, NARRATIVE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "title_id", nullable = false)
    public Title title;

    @Column(name = "ts_seconds", nullable = false)
    public int tsSeconds;

    @Column(name = "end_ts_seconds")
    public Integer endTsSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    public EventType eventType;

    /** Scene name, character name or glossary term. */
    @Column(length = 200)
    public String name;

    @Column(length = 2000)
    public String body;

    /** Comma separated retrieval keywords. */
    @Column(length = 500)
    public String keywords;

    /** Only for VARIATION_POINT: the author-assigned id such as "v_01". */
    @Column(name = "variation_id", length = 64)
    public String variationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "variation_kind", length = 16)
    public VariationKind variationKind;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    public List<VariationOption> options = new ArrayList<>();
}
