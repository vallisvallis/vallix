package com.alphay.boot.web.websocket;

import com.alibaba.fastjson2.JSON;
import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.alphay.boot.web.controller.vallix.VallisUsdtEventTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BTC/USDT 实时价格 WebSocket 处理器。
 * <p>
 * 工作流程：
 * 1. 前端连接 → afterConnectionEstablished 注册 session
 * 2. 定时任务每 3 秒拉取币安最新 1 分钟 K 线
 * 3. 推送 JSON 到所有已连接的客户端
 * 4. 前端断开 → afterConnectionClosed 移除 session
 *
 * @author d3code
 */
@Slf4j
@Component
@EnableScheduling
public class BtcRealTimeHandler extends TextWebSocketHandler {

    @Resource
    private VallisUsdtEventTool eventTool;

    /** 所有已连接的客户端 session */
    private static final Map<String, WebSocketSession> CLIENTS = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("BTC WebSocket 实时推送服务已启动，端点: /ws/btc/realtime");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        CLIENTS.put(session.getId(), session);
        log.info("客户端连接 BTC WebSocket: {} (当前在线: {})", session.getId(), CLIENTS.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        CLIENTS.remove(session.getId());
        log.info("客户端断开 BTC WebSocket: {} (当前在线: {})", session.getId(), CLIENTS.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("BTC WebSocket 传输异常: {}", session.getId(), exception);
        CLIENTS.remove(session.getId());
    }


    /**
     * 获取当前在线客户端数量
     */
    public static int getOnlineCount() {
        return CLIENTS.size();
    }

    /**
     * 定时拉取 BTC 最新数据并广播给所有客户端。
     * fixedRate = 3000ms，每 3 秒执行一次。
     */
    @Scheduled(fixedRate = 3000)
    public void pushBtcTicker() {
        if (CLIENTS.isEmpty()) {
            return;
        }

        try {
            Vallisusdt latest = eventTool.getLatestBtcData();
            if (latest == null) {
                return;
            }

            String json = JSON.toJSONString(latest);
            TextMessage message = new TextMessage(json);

            for (WebSocketSession session : CLIENTS.values()) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(message);
                        }
                    } catch (IOException e) {
                        log.error("推送 BTC 数据失败, session: {}", session.getId(), e);
                        CLIENTS.remove(session.getId());
                    }
                } else {
                    CLIENTS.remove(session.getId());
                }
            }
        } catch (Exception e) {
            log.error("BTC 定时拉取异常", e);
        }
    }
}