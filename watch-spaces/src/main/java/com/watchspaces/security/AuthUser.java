package com.watchspaces.security;

import com.watchspaces.entity.Role;

/** The authenticated principal, derived only from a verified JWT. */
public record AuthUser(long id, String email, Role role) {
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
