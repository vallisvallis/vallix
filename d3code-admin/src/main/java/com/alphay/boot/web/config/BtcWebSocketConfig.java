package com.alphay.boot.web.config;

import com.alphay.boot.web.websocket.EthKlineWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

/**
 * WebSocket 配置
 *
 * @author d3code
 */
@Configuration
@EnableWebSocket
public class BtcWebSocketConfig implements WebSocketConfigurer {

    @Resource
    private EthKlineWebSocketHandler ethKlineWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(ethKlineWebSocketHandler, "/system/vallisusdt/ws/kline")
                .setAllowedOrigins("*");
    }
}