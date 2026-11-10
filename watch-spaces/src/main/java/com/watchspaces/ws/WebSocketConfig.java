package com.watchspaces.ws;

import com.watchspaces.config.AppProperties;
import com.watchspaces.security.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** One socket endpoint per Watch Space: ws://host/ws/watch-spaces/{id}?token=JWT */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RoomSocketHandler handler;
    private final JwtService jwt;
    private final AppProperties props;

    public WebSocketConfig(RoomSocketHandler handler, JwtService jwt, AppProperties props) {
        this.handler = handler;
        this.jwt = jwt;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/watch-spaces/{id}")
                .addInterceptors(new JwtHandshakeInterceptor(jwt))
                .setAllowedOriginPatterns(props.realtime().allowedOrigins().split(","));
    }
}
