package com.watchspaces.service;

import com.watchspaces.config.AppProperties;
import com.watchspaces.entity.RefreshToken;
import com.watchspaces.entity.Role;
import com.watchspaces.entity.User;
import com.watchspaces.repo.RefreshTokenRepository;
import com.watchspaces.repo.UserRepository;
import com.watchspaces.security.JwtService;
import com.watchspaces.util.InputSanitizer;
import com.watchspaces.web.ApiException;
import com.watchspaces.web.Dto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresInSeconds, Dto.UserView user) { }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final long refreshTtlDays;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder encoder,
                       JwtService jwt, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTtlDays = props.jwt().refreshTtlDays();
    }

    /** Self-registration may only create VIEWER or HOST accounts; ADMIN accounts are never self-service. */
    @Transactional
    public Tokens register(String email, String password, String displayName, Role requestedRole, String locale) {
        if (requestedRole == Role.ADMIN) {
            throw ApiException.forbidden("ADMIN accounts cannot be self-registered");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User u = new User();
        u.email = email.trim().toLowerCase();
        u.passwordHash = encoder.encode(password);
        u.displayName = InputSanitizer.text(displayName);
        u.role = requestedRole == null ? Role.VIEWER : requestedRole;
        if (locale != null && !locale.isBlank()) {
            u.subtitleLocale = InputSanitizer.text(locale);
        }
        users.save(u);
        return issue(u);
    }

    @Transactional
    public Tokens login(String email, String password) {
        User u = users.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .filter(x -> encoder.matches(password == null ? "" : password, x.passwordHash))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Invalid email or password"));
        return issue(u);
    }

    /** Rotating refresh: the presented token is revoked and a new pair is issued. */
    @Transactional
    public Tokens refresh(String refreshToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(sha256(refreshToken == null ? "" : refreshToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_REFRESH_TOKEN", "Invalid refresh token"));
        if (stored.revoked || stored.expiresAt.isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_REFRESH_TOKEN", "Refresh token expired or revoked");
        }
        stored.revoked = true;
        refreshTokens.save(stored);
        return issue(stored.user);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.findByTokenHash(sha256(refreshToken == null ? "" : refreshToken)).ifPresent(t -> {
            t.revoked = true;
            refreshTokens.save(t);
        });
    }

    private Tokens issue(User u) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        RefreshToken rt = new RefreshToken();
        rt.user = u;
        rt.tokenHash = sha256(refresh);
        rt.expiresAt = Instant.now().plus(refreshTtlDays, ChronoUnit.DAYS);
        refreshTokens.save(rt);
        return new Tokens(jwt.createAccessToken(u), refresh, "Bearer", jwt.accessTtlSeconds(), Dto.UserView.of(u));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
