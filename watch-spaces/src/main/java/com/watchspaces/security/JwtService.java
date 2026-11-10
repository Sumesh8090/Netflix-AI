package com.watchspaces.security;

import com.watchspaces.config.AppProperties;
import com.watchspaces.entity.Role;
import com.watchspaces.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public record TokenInfo(AuthUser user, String displayName, long expiresAtMs) { }

    private final SecretKey key;
    private final long accessTtlMs;

    public JwtService(AppProperties props) {
        String secret = props.jwt() == null ? null : props.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must be set and at least 32 bytes long");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMs = props.jwt().accessTtlMinutes() * 60_000L;
    }

    public String createAccessToken(User u) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(u.id))
                .claim("email", u.email)
                .claim("role", u.role.name())
                .claim("name", u.displayName)
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessTtlMs))
                .signWith(key)
                .compact();
    }

    public long accessTtlSeconds() {
        return accessTtlMs / 1000;
    }

    /** Returns empty for any invalid, tampered or expired token. */
    public Optional<TokenInfo> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            AuthUser user = new AuthUser(Long.parseLong(c.getSubject()), c.get("email", String.class),
                    Role.valueOf(c.get("role", String.class)));
            return Optional.of(new TokenInfo(user, c.get("name", String.class), c.getExpiration().getTime()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
