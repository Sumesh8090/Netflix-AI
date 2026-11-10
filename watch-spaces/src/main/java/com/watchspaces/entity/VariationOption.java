package com.watchspaces.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "variation_options",
        uniqueConstraints = @UniqueConstraint(name = "uk_option_event_key", columnNames = {"event_id", "option_key"}))
public class VariationOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    public TimelineEvent event;

    @Column(name = "option_key", nullable = false, length = 32)
    public String optionKey;

    @Column(nullable = false, length = 300)
    public String label;

    /** Optional locale this option is the localised variant for (e.g. "hi", "es"). */
    @Column(length = 16)
    public String locale;

    @Column(name = "asset_ref", length = 300)
    public String assetRef;

    /** 0 = the original/default option. */
    @Column(name = "sort_order", nullable = false)
    public int position;
}
