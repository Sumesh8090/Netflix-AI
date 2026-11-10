package com.watchspaces.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchspaces.web.ApiExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Server-side RBAC. Roles: VIEWER, HOST, ADMIN.
 *  - /api/v1/admin/** and /api/v1/metrics/**    : ADMIN only
 *  - POST /api/v1/watch-spaces (create a room)  : HOST or ADMIN
 *  - everything else under /api/**              : any authenticated user (finer checks, e.g. "is the room's host",
 *                                                 are made in the service layer)
 *  - /ws/** is permitted at the HTTP layer because the WebSocket handshake interceptor validates the JWT itself.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, ObjectMapper mapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**", "/actuator/health/**", "/actuator/health",
                                "/api/v1/health/**", "/ws/**").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/metrics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/watch-spaces").hasAnyRole("HOST", "ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            mapper.writeValue(res.getOutputStream(),
                                    ApiExceptionHandler.body("UNAUTHENTICATED", "Authentication is required", null));
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403);
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            mapper.writeValue(res.getOutputStream(),
                                    ApiExceptionHandler.body("FORBIDDEN", "You do not have access to this action", null));
                        }));
        return http.build();
    }
}
