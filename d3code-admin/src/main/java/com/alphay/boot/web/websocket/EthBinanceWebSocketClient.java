//package com.alphay.boot.web.websocket;
//
//import com.alibaba.fastjson2.JSON;
//import com.alibaba.fastjson2.JSONObject;
//import com.alphay.boot.bpm.api.domain.EthKlineSecond;
//import com.alphay.boot.bpm.api.domain.EthTradeRecord;
//import com.alphay.boot.bpm.service.impl.IEthKlineSecondService;
//import com.alphay.boot.bpm.service.impl.IEthTradeRecordService;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.PostConstruct;
//import javax.annotation.PreDestroy;
//import javax.annotation.Resource;
//import javax.websocket.*;
//import java.net.URI;
//import java.util.ArrayList;
//import java.util.*;
//import java.util.concurrent.Executors;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.TimeUnit;
//
///**
// * 币安 ETH/USDT 1秒K线 WebSocket 客户端
// *
// * 启动时自动连接币安 WebSocket 流，接收实时 K 线数据
// * 每收到一条已闭合的 K 线：
// *   1. 存入数据库 eth_kline_second 表（批量写入，每100条或每5秒刷新）
// *   2. 通过 EthKlineWebSocketHandler 广播给所有前端客户端
// *
// * 断线自动重连，5秒重试间隔
// *
// * @author d3code
// */
//@Slf4j
//@Component
//public class EthBinanceWebSocketClient {
//
//    @Autowired
//    private IEthKlineSecondService ethKlineSecondService;
//
//    @Autowired
//    private IEthTradeRecordService ethTradeRecordService;
//
//    @Resource
//    private EthKlineWebSocketHandler ethKlineWebSocketHandler;
//
//    private Session binanceSession;
//    private volatile boolean running = true;
//
//    /** K线写入缓冲队列（批量刷入DB） */
//    private final List<EthKlineSecond> writeBuffer = Collections.synchronizedList(new ArrayList<>());
//    private static final int BATCH_SIZE = 100;
//
//    // ==================== 策略引擎 ====================
//    /** 20分钟滑动窗口（1200秒） */
//    private final LinkedList<EthKlineSecond> klineWindow = new LinkedList<>();
//    private static final int WINDOW_SECONDS = 1200;
//    private static final int TREND_SECONDS = 300;       // 5分钟趋势回看
//    private static final double TREND_THRESHOLD = 0.003; // 趋势阈值 0.3%
//    private static final long COOLING_US = 60_000_000L;  // 1分钟冷却（微秒）
//    private static final long SETTLE_US = 600_000_000L;   // 10分钟结算（微秒）
//
//    /** 待结算交易 */
//    private final List<PendingTrade> pendingTrades = new ArrayList<>();
//    private long lastTradeTs = 0;
//    private long lastMaxTs = -1;
//    private long lastMinTs = -1;
//
//    @PostConstruct
//    public void init() {
//        log.info("🚀 ETH Binance WebSocket 客户端启动中...");
//        new Thread(this::connect, "binance-ws").start();
//
//        // 定时刷新缓冲（兜底，防止最后一波数据滞留）
//        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
//        scheduler.scheduleAtFixedRate(this::flushBuffer, 5, 5, TimeUnit.SECONDS);
//    }
//
//    @PreDestroy
//    public void destroy() {
//        running = false;
//        flushBuffer();
//        if (binanceSession != null) {
//            try { binanceSession.close(); } catch (Exception ignored) {}
//        }
//        log.info("ETH Binance WebSocket 客户端已关闭");
//    }
//
//    /**
//     * 连接币安 WebSocket 1秒K线流，断线自动重连
//     */
//    private void connect() {
//        while (running) {
//            try {
//                String wsUrl = "wss://data-stream.binance.vision/ws/ethusdt@kline_1s";
//                log.info("🔗 正在连接币安 WebSocket: {}", wsUrl);
//
//                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
//                container.setDefaultMaxSessionIdleTimeout(0);
//                container.connectToServer(new BinanceEndpoint(), URI.create(wsUrl));
//
//                log.info("✅ 币安 WebSocket 连接成功");
//                // 阻塞直到连接关闭
//                while (running && binanceSession != null && binanceSession.isOpen()) {
//                    Thread.sleep(1000);
//                }
//            } catch (Exception e) {
//                log.error("币安 WebSocket 连接异常: {}", e.getMessage());
//            }
//
//            if (running) {
//                log.info("🔄 5秒后尝试重连...");
//                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
//            }
//        }
//    }
//
//    /**
//     * 批量刷新K线缓冲到数据库
//     */
//    private void flushBuffer() {
//        List<EthKlineSecond> batch;
//        synchronized (writeBuffer) {
//            if (writeBuffer.isEmpty()) return;
//            batch = new ArrayList<>(writeBuffer);
//            writeBuffer.clear();
//        }
//        try {
//            ethKlineSecondService.saveBatchKlineData(batch);
//            log.debug("K线缓冲刷新: {} 条", batch.size());
//        } catch (Exception e) {
//            log.error("K线缓冲刷新失败", e);
//        }
//    }
//
//    /**
//     * 执行交易策略（与回测逻辑一致）
//     * 20分钟窗口极值 + 5分钟趋势过滤 + 极值去重 + 1分钟冷却
//     */
//    private void executeStrategy(EthKlineSecond current) {
//        if (klineWindow.size() < WINDOW_SECONDS) return;
//
//        long curTs = current.getTimestamp();
//        double curPrice = parseDouble(current.getClose());
//
//        // 1. 20分钟窗口极值计算
//        long maxTs = 0, minTs = Long.MAX_VALUE;
//        double maxPrice = -1, minPrice = Double.MAX_VALUE;
//        for (EthKlineSecond k : klineWindow) {
//            double price = parseDouble(k.getClose());
//            long ts = k.getTimestamp();
//            if (price > maxPrice) { maxPrice = price; maxTs = ts; }
//            if (price < minPrice) { minPrice = price; minTs = ts; }
//        }
//
//        // 2. 5分钟趋势判断
//        long trendStart = curTs - TREND_SECONDS * 1_000_000L;
//        double trendOldPrice = curPrice;
//        for (EthKlineSecond k : klineWindow) {
//            if (k.getTimestamp() >= trendStart) {
//                trendOldPrice = parseDouble(k.getClose());
//                break;
//            }
//        }
//        double trendChange = (curPrice - trendOldPrice) / trendOldPrice;
//        boolean trendUp = trendChange > TREND_THRESHOLD;
//        boolean trendDown = trendChange < -TREND_THRESHOLD;
//
//        // 3. 冷却检查
//        if (curTs - lastTradeTs < COOLING_US) return;
//
//        // 4. 空单：最高价出现1~3分钟，极值未重复，趋势非上涨
//        long maxAge = (curTs - maxTs) / 1_000_000L;
//        if (maxAge >= 60 && maxAge <= 180 && maxTs != lastMaxTs && !trendUp) {
//            PendingTrade trade = new PendingTrade(curTs, curTs + SETTLE_US, "空单", curPrice, maxPrice, minPrice, maxTs, minTs);
//            // 保存到数据库
//            EthTradeRecord dbRecord = ethTradeRecordService.createTrade(trade.toDbRecord());
//            trade.dbId = dbRecord.getId();
//            pendingTrades.add(trade);
//            lastTradeTs = curTs;
//            lastMaxTs = maxTs;
//            broadcastTrade(trade.toOpenJson());
//            log.info("📉 开空单 @{} | 最高{} | 趋势{}%",
//                    String.format("%.4f", curPrice), String.format("%.4f", maxPrice),
//                    String.format("%.2f", trendChange * 100));
//        }
//
//        // 5. 多单：最低价出现1~3分钟，极值未重复，趋势非下跌
//        long minAge = (curTs - minTs) / 1_000_000L;
//        if (minAge >= 60 && minAge <= 180 && minTs != lastMinTs && !trendDown) {
//            PendingTrade trade = new PendingTrade(curTs, curTs + SETTLE_US, "多单", curPrice, maxPrice, minPrice, maxTs, minTs);
//            EthTradeRecord dbRecord = ethTradeRecordService.createTrade(trade.toDbRecord());
//            trade.dbId = dbRecord.getId();
//            pendingTrades.add(trade);
//            lastTradeTs = curTs;
//            lastMinTs = minTs;
//            broadcastTrade(trade.toOpenJson());
//            log.info("📈 开多单 @{} | 最低{} | 趋势{}%",
//                    String.format("%.4f", curPrice), String.format("%.4f", minPrice),
//                    String.format("%.2f", trendChange * 100));
//        }
//    }
//
//    /** 检查待结算交易，到期自动结算 */
//    private void checkSettlements(EthKlineSecond current) {
//        long curTs = current.getTimestamp();
//        double settlePrice = parseDouble(current.getClose());
//
//        Iterator<PendingTrade> it = pendingTrades.iterator();
//        while (it.hasNext()) {
//            PendingTrade trade = it.next();
//            if (curTs >= trade.settleTime) {
//                double profit = "多单".equals(trade.direction)
//                        ? settlePrice - trade.openPrice : trade.openPrice - settlePrice;
//                String status = profit >= 0 ? "盈利" : "亏损";
//                // 更新数据库
//                ethTradeRecordService.settleTrade(trade.dbId, curTs,
//                        String.format("%.4f", settlePrice),
//                        String.format("%.2f", profit),
//                        String.format("%.2f", profit / trade.openPrice * 100), status);
//                broadcastTrade(trade.toSettleJson(settlePrice, profit));
//                log.info("💰 {}结算: 开{} 平{} {}${}", trade.direction,
//                        String.format("%.4f", trade.openPrice), String.format("%.4f", settlePrice),
//                        profit >= 0 ? "+" : "", String.format("%.2f", profit));
//                it.remove();
//            }
//        }
//    }
//
//    private void broadcastTrade(JSONObject tradeJson) {
//        JSONObject msg = new JSONObject();
//        msg.put("code", 200);
//        msg.put("trade", tradeJson);
//        ethKlineWebSocketHandler.broadcast(msg.toJSONString());
//    }
//
//    private double parseDouble(String s) {
//        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
//    }
//
//    /** 待结算交易 */
//    static class PendingTrade {
//        long dbId;
//        long timestamp, settleTime;
//        String direction;
//        double openPrice, high20min, low20min;
//        long high20minTime, low20minTime;
//
//        PendingTrade(long ts, long st, String dir, double op, double hi, double lo, long hit, long lit) {
//            this.timestamp = ts; this.settleTime = st; this.direction = dir;
//            this.openPrice = op; this.high20min = hi; this.low20min = lo;
//            this.high20minTime = hit; this.low20minTime = lit;
//        }
//
//        EthTradeRecord toDbRecord() {
//            return EthTradeRecord.builder()
//                    .openTimestamp(timestamp)
//                    .direction(direction)
//                    .openPrice(String.format("%.4f", openPrice))
//                    .high20min(String.format("%.4f", high20min))
//                    .high20minTime(String.valueOf(high20minTime))
//                    .low20min(String.format("%.4f", low20min))
//                    .low20minTime(String.valueOf(low20minTime))
//                    .status("待结算")
//                    .build();
//        }
//
//        JSONObject toOpenJson() {
//            JSONObject t = new JSONObject();
//            t.put("type", "open");
//            t.put("direction", direction);
//            t.put("time", timestamp);
//            t.put("openPrice", String.format("%.4f", openPrice));
//            t.put("high20min", String.format("%.4f", high20min));
//            t.put("low20min", String.format("%.4f", low20min));
//            return t;
//        }
//
//        JSONObject toSettleJson(double settlePrice, double profit) {
//            JSONObject t = toOpenJson();
//            t.put("type", "settle");
//            t.put("settlePrice", String.format("%.4f", settlePrice));
//            t.put("profit", String.format("%.2f", profit));
//            t.put("profitPercent", String.format("%.2f", profit / openPrice * 100));
//            t.put("status", profit >= 0 ? "盈利" : "亏损");
//            return t;
//        }
//    }
//
//    /**
//     * 币安 WebSocket 客户端端点
//     * 接收 1秒 K线数据，存入数据库并广播给前端
//     */
//    @ClientEndpoint
//    public class BinanceEndpoint {
//
//        @OnOpen
//        public void onOpen(Session session) {
//            binanceSession = session;
//            log.info("✅ 币安端点已打开: {}", session.getId());
//        }
//
//        @OnMessage
//        public void onMessage(String message) {
//            try {
//                JSONObject json = JSON.parseObject(message);
//                JSONObject kline = json.getJSONObject("k");
//                if (kline == null || !kline.getBooleanValue("x")) {
//                    return; // 只处理已闭合的K线
//                }
//
//                long timestamp = kline.getLong("t") * 1000; // 毫秒 → 微秒
//                String open = kline.getString("o");
//                String close = kline.getString("c");
//                String high = kline.getString("h");
//                String low = kline.getString("l");
//                String volume = kline.getString("v");
//
//                // 1. 存入缓冲（批量写DB）
//                EthKlineSecond entity = EthKlineSecond.builder()
//                        .timestamp(timestamp)
//                        .open(open)
//                        .close(close)
//                        .high(high)
//                        .low(low)
//                        .volume(volume)
//                        .build();
//                writeBuffer.add(entity);
//                if (writeBuffer.size() >= BATCH_SIZE) {
//                    flushBuffer();
//                }
//
//                // 2. 广播给前端（包装为数组，匹配前端 data.data.map() 期望格式）
//                JSONObject item = new JSONObject();
//                item.put("startTime", kline.getLong("t"));
//                item.put("open", open);
//                item.put("close", close);
//                item.put("high", high);
//                item.put("low", low);
//                item.put("volume", volume);
//
//                JSONObject msg = new JSONObject();
//                msg.put("code", 200);
//                msg.put("data", new JSONObject[]{item});
//                ethKlineWebSocketHandler.broadcast(msg.toJSONString());
//
//                // 3. 策略引擎：滑动窗口 + 极值检测 + 趋势过滤 + 开单/结算
//                klineWindow.addLast(entity);
//                while (klineWindow.size() > WINDOW_SECONDS) {
//                    klineWindow.removeFirst();
//                }
//                executeStrategy(entity);
//                checkSettlements(entity);
//
//            } catch (Exception e) {
//                log.error("处理币安K线消息异常", e);
//            }
//        }
//
//        @OnClose
//        public void onClose(Session session, CloseReason reason) {
//            log.warn("⚠️ 币安 WebSocket 断开: {}", reason.getReasonPhrase());
//            binanceSession = null;
//        }
//
//        @OnError
//        public void onError(Session session, Throwable thr) {
//            log.error("❌ 币安 WebSocket 错误: {}", thr.getMessage());
//        }
//    }
//}