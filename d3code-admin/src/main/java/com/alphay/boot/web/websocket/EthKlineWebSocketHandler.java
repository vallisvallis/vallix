package com.alphay.boot.web.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alphay.boot.web.controller.vallix.VallisUsdtEventTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ETH/USDT K线实时数据 WebSocket 处理器
 *
 * 支持订阅不同时间周期的K线数据
 *
 * @author d3code
 */
@Slf4j
// @Component  // 暂停使用，避免与实时交易 WebSocket 客户端冲突
public class EthKlineWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private VallisUsdtEventTool eventTool;

    /** 所有已连接的客户端 session，存储订阅的时间周期 */
    private static final Map<String, SessionInfo> CLIENTS = new ConcurrentHashMap<>();

    /** 订阅的时间周期枚举 */
    public enum Interval {
        MIN_1("1m"),
        MIN_5("5m"),
        MIN_10("10m"),
        MIN_15("15m"),
        HOUR_1("1h"),
        HOUR_4("4h"),
        DAY_1("1d");

        private final String value;

        Interval(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Interval fromValue(String value) {
            for (Interval interval : values()) {
                if (interval.value.equalsIgnoreCase(value)) {
                    return interval;
                }
            }
            return MIN_1; // 默认1分钟
        }
    }

    /** 客户端会话信息 */
    static class SessionInfo {
        WebSocketSession session;
        Interval interval;

        SessionInfo(WebSocketSession session, Interval interval) {
            this.session = session;
            this.interval = interval;
        }
    }

    @PostConstruct
    public void init() {
        log.info("ETH Kline WebSocket 实时推送服务已启动，端点: /system/vallisusdt/ws/kline");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 默认订阅1分钟K线
        CLIENTS.put(session.getId(), new SessionInfo(session, Interval.MIN_1));
        log.info("客户端连接 ETH Kline WebSocket: {} (当前在线: {})", session.getId(), CLIENTS.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String payload = message.getPayload();
            JSONObject json = JSON.parseObject(payload);

            String action = json.getString("action");
            String intervalStr = json.getString("interval");

            if ("subscribe".equals(action)) {
                Interval interval = Interval.fromValue(intervalStr);
                SessionInfo info = CLIENTS.get(session.getId());
                if (info != null) {
                    info.interval = interval;
                    log.info("客户端 {} 订阅 {} K线", session.getId(), interval.getValue());
                }
            } else if ("unsubscribe".equals(action)) {
                CLIENTS.remove(session.getId());
                log.info("客户端 {} 取消订阅", session.getId());
            }
        } catch (Exception e) {
            log.error("处理 ETH WebSocket 消息异常: {}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        CLIENTS.remove(session.getId());
        log.info("客户端断开 ETH Kline WebSocket: {} (当前在线: {})", session.getId(), CLIENTS.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("ETH Kline WebSocket 传输异常: {}", session.getId(), exception);
        CLIENTS.remove(session.getId());
    }

    /**
     * 获取当前在线客户端数量
     */
    public static int getOnlineCount() {
        return CLIENTS.size();
    }


    /**
     * 定时拉取 ETH K线数据并广播给所有客户端
     * fixedRate = 3000ms，每3秒执行一次
     */
    // @Scheduled(fixedRate = 3000)  // 暂停定时拉取，避免与实时交易 WebSocket 客户端冲突
    public void pushEthKlineData() {
        if (CLIENTS.isEmpty()) {
            return;
        }

        try {
            // 获取所有订阅的时间周期
            for (Map.Entry<String, SessionInfo> entry : CLIENTS.entrySet()) {
                SessionInfo info = entry.getValue();
                WebSocketSession session = info.session;

                if (!session.isOpen()) {
                    CLIENTS.remove(entry.getKey());
                    continue;
                }

                try {
                    List<?> klineData = eventTool.getEthKline(100, info.interval.getValue());

                    JSONObject response = new JSONObject();
                    response.put("code", 200);
                    response.put("data", klineData);

                    String json = JSON.toJSONString(response);
                    TextMessage message = new TextMessage(json);

                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (IOException e) {
                    log.error("推送 ETH K线数据失败, session: {}", session.getId(), e);
                    CLIENTS.remove(session.getId());
                }
            }
        } catch (Exception e) {
            log.error("ETH Kline 定时拉取异常", e);
        }
    }
}