package com.watchspaces.ws;

import com.watchspaces.security.JwtService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Authenticates the WebSocket upgrade with the same JWT as REST. Rejects with 401 before any socket exists. */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {
    public static final String ATTR_USER = "user";
    public static final String ATTR_NAME = "name";
    public static final String ATTR_EXPIRES = "expiresAt";
    public static final String ATTR_WS_ID = "watchSpaceId";
    /** Optional ?locale=hi-IN on the upgrade request; validated, ignored when malformed. */
    public static final String ATTR_LOCALE = "locale";

    private final JwtService jwt;

    public JwtHandshakeInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = null;
        if (request instanceof ServletServerHttpRequest r) {
            token = r.getServletRequest().getParameter("token");
        }
        String header = request.getHeaders().getFirst("Authorization");
        if (token == null && header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        }
        var info = jwt.parse(token);
        if (info.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (request instanceof ServletServerHttpRequest r) {
            String loc = com.watchspaces.core.VariationRules.normalizeLocale(r.getServletRequest().getParameter("locale"));
            if (loc != null) {
                attributes.put(ATTR_LOCALE, loc);
            }
        }
        String path = request.getURI().getPath();
        try {
            attributes.put(ATTR_WS_ID, Long.parseLong(path.substring(path.lastIndexOf('/') + 1)));
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        attributes.put(ATTR_USER, info.get().user());
        attributes.put(ATTR_NAME, info.get().displayName());
        attributes.put(ATTR_EXPIRES, info.get().expiresAtMs());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
        // nothing to do
    }
}
