package com.alphay.boot.web.config;

import com.alphay.boot.web.websocket.BtcRealTimeHandler;
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
// @Configuration  // 暂停 WebSocket 服务端，避免与实时交易客户端冲突
// @EnableWebSocket
public class BtcWebSocketConfig implements WebSocketConfigurer {

    @Resource
    private BtcRealTimeHandler btcRealTimeHandler;

    @Resource
    private EthKlineWebSocketHandler ethKlineWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(btcRealTimeHandler, "/ws/btc/realtime")
                .setAllowedOrigins("*");
        
        registry.addHandler(ethKlineWebSocketHandler, "/system/vallisusdt/ws/kline")
                .setAllowedOrigins("*");
    }
}