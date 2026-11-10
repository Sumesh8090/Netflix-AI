package com.watchspaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed view of the "app.*" settings in application.yml. Secrets come from environment variables. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, @DefaultValue Seed seed, @DefaultValue Realtime realtime) {

    public record Jwt(String secret,
                      @DefaultValue("60") long accessTtlMinutes,
                      @DefaultValue("7") long refreshTtlDays) { }

    public record Seed(@DefaultValue("false") boolean enabled, String defaultPassword) { }

    public record Realtime(@DefaultValue("15000") long hostGraceMs,
                           @DefaultValue("5000") long reconnectGraceMs,
                           @DefaultValue("250") long triviaTickMs,
                           @DefaultValue("*") String allowedOrigins,
                           @DefaultValue("600000") long emptyRoomTtlMs) { }
}
