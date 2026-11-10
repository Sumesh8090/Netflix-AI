package com.watchspaces.web;

import com.watchspaces.entity.Role;
import com.watchspaces.entity.User;
import com.watchspaces.repo.UserRepository;
import com.watchspaces.security.AuthUser;
import com.watchspaces.service.AuthService;
import com.watchspaces.util.InputSanitizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    public record RegisterRequest(@NotBlank @Email @Size(max = 190) String email,
                                  @NotBlank @Size(min = 8, max = 72) String password,
                                  @NotBlank @Size(max = 80) String displayName,
                                  Role role,
                                  @Size(max = 16) String subtitleLocale) { }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) { }

    public record RefreshRequest(@NotBlank String refreshToken) { }

    public record PreferencesRequest(@NotBlank @Size(max = 16) String subtitleLocale) { }

    private final AuthService auth;
    private final UserRepository users;

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Tokens register(@Valid @RequestBody RegisterRequest r) {
        return auth.register(r.email(), r.password(), r.displayName(), r.role(), r.subtitleLocale());
    }

    @PostMapping("/auth/login")
    public AuthService.Tokens login(@Valid @RequestBody LoginRequest r) {
        return auth.login(r.email(), r.password());
    }

    @PostMapping("/auth/refresh")
    public AuthService.Tokens refresh(@Valid @RequestBody RefreshRequest r) {
        return auth.refresh(r.refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest r) {
        auth.logout(r.refreshToken());
    }

    @GetMapping("/me")
    public Dto.UserView me(@AuthenticationPrincipal AuthUser principal) {
        return Dto.UserView.of(users.findById(principal.id()).orElseThrow(() -> ApiException.notFound("User")));
    }

    @PatchMapping("/me/preferences")
    public Dto.UserView preferences(@AuthenticationPrincipal AuthUser principal, @Valid @RequestBody PreferencesRequest r) {
        User u = users.findById(principal.id()).orElseThrow(() -> ApiException.notFound("User"));
        u.subtitleLocale = InputSanitizer.text(r.subtitleLocale());
        return Dto.UserView.of(users.save(u));
    }
}
