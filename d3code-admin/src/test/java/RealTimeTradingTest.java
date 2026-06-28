package com.alphay.boot.web.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alphay.boot.bpm.api.domain.EthTradeRecord;
import com.alphay.boot.bpm.service.IEthTradeRecordService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.websocket.*;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时交易测试 - WebSocket 连接币安，实时计算并下单
 *
 * 策略：
 * 1. 连接币安 WebSocket 获取 ETH/USDT 1秒 K线实时数据
 * 2. 维护过去 20 分钟的数据缓冲
 * 3. 在 20 分钟窗口内找到最高价/最低价
 * 4. 开单条件：当前时间比极值时间晚 1~3 分钟，且每分钟最多开一单
 * 5. 开单后存入数据库，10 分钟后自动结算
 * 6. 运行一整夜，第二天早上查看结果
 *
 * 使用方法：
 * 1. 先执行 SQL 建表脚本
 * 2. 运行此测试类
 * 3. 第二天早上查看 eth_trade_record 表
 *
 * @author d3code
 */
@Slf4j
@SpringBootTest
public class RealTimeTradingTest {

    @Autowired
    private IEthTradeRecordService tradeRecordService;

    // ===== 时间常量（微秒）=====
    private static final long TWENTY_MINUTES_US = 1_200_000_000L;
    private static final long TEN_MINUTES_US = 600_000_000L;
    private static final long ONE_MINUTE_US = 60_000_000L;
    private static final long THREE_MINUTES_US = 180_000_000L;

    // ===== 数据缓冲 =====
    private final ConcurrentSkipListMap<Long, KlineData> dataBuffer = new ConcurrentSkipListMap<>();

    // ===== 待结算交易 =====
    private final ConcurrentHashMap<Long, PendingTrade> pendingTrades = new ConcurrentHashMap<>();

    // ===== 策略状态 =====
    private long lastTradeTs = -1;
    private long lastShortTs = -1;
    private long lastLongTs = -1;

    // ===== 统计 =====
    private final AtomicInteger totalTrades = new AtomicInteger(0);
    private final AtomicInteger shortTrades = new AtomicInteger(0);
    private final AtomicInteger longTrades = new AtomicInteger(0);
    private final AtomicInteger settledCount = new AtomicInteger(0);
    private final AtomicInteger winCount = new AtomicInteger(0);
    private final AtomicInteger loseCount = new AtomicInteger(0);
    private final AtomicInteger timeoutCount = new AtomicInteger(0);

    // ===== 控制 =====
    private static RealTimeTradingTest self;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile boolean running = true;
    private volatile Session binanceSession;
    private long startTime;

    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * K线数据 DTO
     */
    static class KlineData {
        long timestamp;   // 微秒
        double open;
        double close;
        double high;
        double low;
        double volume;
    }

    /**
     * 待结算交易
     */
    static class PendingTrade {
        Long dbId;
        long openTs;
        long targetTs;
        String direction;
        String openPrice;
    }

    @Test
    public void testPersistence() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║        持久化验证 - eth_trade_record         ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        try {
            // 1. 创建测试记录
            System.out.println("1️⃣ 测试创建开单记录...");
            long now = System.currentTimeMillis() * 1000;
            EthTradeRecord record = EthTradeRecord.builder()
                    .openTimestamp(now)
                    .direction("空单")
                    .openPrice("2500.12345678")
                    .high20min("2505.00000000")
                    .high20minTime("2026-06-28 12:00:00")
                    .low20min("2495.00000000")
                    .low20minTime("2026-06-28 11:50:00")
                    .build();

            tradeRecordService.createTrade(record);
            Long testId = record.getId();
            System.out.println("   ✅ 创建成功, id=" + testId + ", status=" + record.getStatus());

            if (testId == null) {
                System.err.println("   ❌ 创建失败: id 为 null");
                return;
            }

            // 2. 回查验证
            System.out.println("2️⃣ 测试回查记录...");
            EthTradeRecord saved = tradeRecordService.getById(testId);
            if (saved == null) {
                System.err.println("   ❌ 回查失败: 记录不存在");
                return;
            }
            System.out.println("   ✅ 回查成功: 方向=" + saved.getDirection()
                    + ", 价格=" + saved.getOpenPrice()
                    + ", 状态=" + saved.getStatus());

            // 3. 测试结算
            System.out.println("3️⃣ 测试结算记录...");
            long settleTs = now + 600_000_000L; // 10分钟后
            boolean settled = tradeRecordService.settleTrade(testId, settleTs,
                    "2501.00000000", "0.87654322", "0.04", "盈利");
            if (!settled) {
                System.err.println("   ❌ 结算失败");
                return;
            }

            EthTradeRecord settledRecord = tradeRecordService.getById(testId);
            System.out.println("   ✅ 结算成功: 平仓价=" + settledRecord.getClosePrice()
                    + ", 收益=" + settledRecord.getProfit()
                    + ", 状态=" + settledRecord.getStatus());

            // 4. 测试统计
            System.out.println("4️⃣ 测试统计查询...");
            IEthTradeRecordService.TradeStats stats = tradeRecordService.getStats();
            System.out.println("   ✅ 统计: 总交易=" + stats.totalTrades
                    + ", 待结算=" + stats.pendingTrades
                    + ", 盈利=" + stats.winCount);

            // 5. 清理测试数据
            System.out.println("5️⃣ 清理测试数据...");
            tradeRecordService.removeById(testId);
            System.out.println("   ✅ 测试数据已清理");

            System.out.println();
            System.out.println("╔══════════════════════════════════════════════╗");
            System.out.println("║          ✅ 持久化验证全部通过！              ║");
            System.out.println("╚══════════════════════════════════════════════╝");

        } catch (Exception e) {
            System.err.println("❌ 持久化验证失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    public void testRealTimeTrading() throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     ETH/USDT 实时交易策略 - WebSocket 版     ║");
        System.out.println("║     运行一整夜，自动开单 + 自动结算           ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("策略规则:");
        System.out.println("  ├─ 数据源: 币安 WebSocket 1秒K线 (data-stream.binance.vision)");
        System.out.println("  ├─ 窗口: 过去20分钟");
        System.out.println("  ├─ 空单: 20分钟内最高价出现后 1~3 分钟开空");
        System.out.println("  ├─ 多单: 20分钟内最低价出现后 1~3 分钟开多");
        System.out.println("  ├─ 冷却: 每分钟最多开一单");
        System.out.println("  ├─ 结算: 开单 10 分钟后自动结算");
        System.out.println("  └─ 存储: 实时写入 eth_trade_record 表");
        System.out.println();

        startTime = System.currentTimeMillis();
        self = this;

        // 连接币安 WebSocket
        connectBinanceWebSocket();

        // 启动定时任务：结算检查 + 状态打印 + 数据清理
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
        scheduler.scheduleAtFixedRate(this::checkPendingSettlements, 10, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::printStatus, 60, 300, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::cleanupOldData, 120, 60, TimeUnit.SECONDS);

        // 添加 JVM 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 正在关闭...");
            running = false;
            printFinalReport();
            shutdownLatch.countDown();
        }));

        // 主线程阻塞，保持运行
        System.out.println("✅ 实时交易系统已启动，开始监控...");
        System.out.println("   按 Ctrl+C 停止运行\n");
        shutdownLatch.await();
    }

    /**
     * 连接币安 WebSocket 1秒K线流
     */
    private void connectBinanceWebSocket() throws Exception {
        String wsUrl = "wss://data-stream.binance.vision/ws/ethusdt@kline_1s";
        System.out.println("🔗 正在连接币安 WebSocket: " + wsUrl);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxSessionIdleTimeout(0);

        container.connectToServer(BinanceClientEndpoint.class, URI.create(wsUrl));

        // 等待连接建立
        int retries = 0;
        while (binanceSession == null && retries < 30) {
            Thread.sleep(1000);
            retries++;
        }
        if (binanceSession == null) {
            throw new RuntimeException("连接币安 WebSocket 超时");
        }
    }

    /**
     * 币安 WebSocket 客户端端点
     */
    @ClientEndpoint
    public static class BinanceClientEndpoint {

        @OnOpen
        public void onOpen(Session session) {
            self.binanceSession = session;
            System.out.println("✅ 币安 WebSocket 连接成功");
            session.addMessageHandler(new MessageHandler.Whole<String>() {
                @Override
                public void onMessage(String message) {
                    self.handleKlineMessage(message);
                }
            });
        }

        @OnClose
        public void onClose(Session session, CloseReason reason) {
            System.out.println("⚠️ 币安 WebSocket 断开: " + reason.getReasonPhrase());
            self.binanceSession = null;
            if (self.running) {
                System.out.println("🔄 5秒后尝试重连...");
                try {
                    Thread.sleep(5000);
                    self.connectBinanceWebSocket();
                } catch (Exception e) {
                    log.error("重连失败", e);
                }
            }
        }

        @OnError
        public void onError(Session session, Throwable thr) {
            System.err.println("❌ 币安 WebSocket 错误: " + thr.getMessage());
        }
    }

    /**
     * 处理币安 K线消息
     */
    private void handleKlineMessage(String message) {
        try {
            JSONObject json = JSON.parseObject(message);
            JSONObject kline = json.getJSONObject("k");

            if (kline == null || !kline.getBooleanValue("x")) {
                return; // 只处理已闭合的K线
            }

            KlineData data = new KlineData();
            // 币安返回毫秒，转换为微秒
            data.timestamp = kline.getLong("t") * 1000;
            data.open = kline.getDouble("o");
            data.close = kline.getDouble("c");
            data.high = kline.getDouble("h");
            data.low = kline.getDouble("l");
            data.volume = kline.getDouble("v");

            dataBuffer.put(data.timestamp, data);

            // 执行策略
            executeStrategy(data);

        } catch (Exception e) {
            // 忽略解析错误
        }
    }

    /**
     * 执行交易策略（与 TradingStrategyBacktest 逻辑一致）
     */
    private synchronized void executeStrategy(KlineData currentData) {
        long currentTs = currentData.timestamp;
        double currentPrice = currentData.close;

        // 获取过去20分钟的数据
        long windowStart = currentTs - TWENTY_MINUTES_US;
        NavigableMap<Long, KlineData> windowData = dataBuffer.subMap(windowStart, true, currentTs, false);

        if (windowData.size() < 1200) {
            return; // 不足20分钟数据，跳过
        }

        // 找到20分钟内的最高价和最低价
        KlineData maxData = null;
        KlineData minData = null;
        double maxPrice = Double.MIN_VALUE;
        double minPrice = Double.MAX_VALUE;

        for (KlineData kd : windowData.values()) {
            if (kd.close > maxPrice) {
                maxPrice = kd.close;
                maxData = kd;
            }
            if (kd.close < minPrice) {
                minPrice = kd.close;
                minData = kd;
            }
        }

        if (maxData == null || minData == null) return;

        long maxTs = maxData.timestamp;
        long minTs = minData.timestamp;

        // 空单：基于最高价，开单时间在最高价出现后 1~3 分钟
        if (currentTs - maxTs >= ONE_MINUTE_US && currentTs - maxTs <= THREE_MINUTES_US
                && currentTs > maxTs && maxTs != lastShortTs
                && (lastTradeTs == -1 || currentTs - lastTradeTs >= ONE_MINUTE_US)) {

            createTrade(maxData, minData, "空单", currentTs, currentPrice);
            lastShortTs = maxTs;
            lastTradeTs = currentTs;
        }

        // 多单：基于最低价，开单时间在最低价出现后 1~3 分钟
        if (currentTs - minTs >= ONE_MINUTE_US && currentTs - minTs <= THREE_MINUTES_US
                && currentTs > minTs && minTs != lastLongTs
                && (lastTradeTs == -1 || currentTs - lastTradeTs >= ONE_MINUTE_US)) {

            createTrade(maxData, minData, "多单", currentTs, currentPrice);
            lastLongTs = minTs;
            lastTradeTs = currentTs;
        }
    }

    /**
     * 创建开单记录并存入数据库，同时加入待结算队列
     */
    private void createTrade(KlineData maxData, KlineData minData,
                             String direction, long openTs, double openPrice) {
        try {
            String openTimeStr = formatTimestamp(openTs);
            String maxTimeStr = formatTimestamp(maxData.timestamp);
            String minTimeStr = formatTimestamp(minData.timestamp);

            EthTradeRecord record = EthTradeRecord.builder()
                    .openTimestamp(openTs)
                    .direction(direction)
                    .openPrice(String.format("%.8f", openPrice))
                    .high20min(String.format("%.8f", maxData.close))
                    .high20minTime(maxTimeStr)
                    .low20min(String.format("%.8f", minData.close))
                    .low20minTime(minTimeStr)
                    .build();

            tradeRecordService.createTrade(record);

            // 加入待结算队列
            PendingTrade pt = new PendingTrade();
            pt.dbId = record.getId();
            pt.openTs = openTs;
            pt.targetTs = openTs + TEN_MINUTES_US;
            pt.direction = direction;
            pt.openPrice = String.format("%.8f", openPrice);
            pendingTrades.put(pt.dbId, pt);

            totalTrades.incrementAndGet();
            if ("空单".equals(direction)) shortTrades.incrementAndGet();
            else longTrades.incrementAndGet();

            System.out.println(String.format("📊 [%s] %s 开仓 $%s | 20min最高:$%s(%s) 最低:$%s(%s)",
                    openTimeStr, direction, String.format("%.4f", openPrice),
                    String.format("%.4f", maxData.close), maxTimeStr,
                    String.format("%.4f", minData.close), minTimeStr));

        } catch (Exception e) {
            System.err.println("❌ 创建开单记录失败: " + e.getMessage());
        }
    }

    /**
     * 检查待结算交易，10分钟后自动结算
     */
    private void checkPendingSettlements() {
        if (pendingTrades.isEmpty()) return;

        long now = System.currentTimeMillis() * 1000; // 当前时间（微秒）
        List<Long> toRemove = new ArrayList<>();

        for (Map.Entry<Long, PendingTrade> entry : pendingTrades.entrySet()) {
            PendingTrade pt = entry.getValue();

            // 检查是否已到目标时间
            if (now < pt.targetTs) continue;

            // 查找目标时间的数据
            KlineData targetData = dataBuffer.get(pt.targetTs);

            if (targetData != null) {
                // 有数据，正常结算
                double openPrice = Double.parseDouble(pt.openPrice);
                double closePrice = targetData.close;
                double profit;
                if ("多单".equals(pt.direction)) {
                    profit = closePrice - openPrice;
                } else {
                    profit = openPrice - closePrice;
                }
                double profitPercent = (profit / openPrice) * 100;
                String status = profit > 0 ? "盈利" : "亏损";

                tradeRecordService.settleTrade(pt.dbId, pt.targetTs,
                        String.format("%.8f", closePrice),
                        String.format("%.8f", profit),
                        String.format("%.2f", profitPercent),
                        status);

                settledCount.incrementAndGet();
                if (profit > 0) winCount.incrementAndGet();
                else loseCount.incrementAndGet();

                toRemove.add(entry.getKey());

            } else if (now - pt.targetTs > 5 * 60_000_000L) {
                // 超时5分钟还没有数据，标记为超时
                tradeRecordService.settleTrade(pt.dbId, pt.targetTs,
                        "0", "0", "0", "超时");
                timeoutCount.incrementAndGet();
                toRemove.add(entry.getKey());
            }
        }

        for (Long id : toRemove) {
            pendingTrades.remove(id);
        }
    }

    /**
     * 清理超过30分钟的旧数据
     */
    private void cleanupOldData() {
        long cutoff = System.currentTimeMillis() * 1000 - 30 * 60_000_000L;
        int removed = 0;
        while (!dataBuffer.isEmpty() && dataBuffer.firstKey() < cutoff) {
            dataBuffer.pollFirstEntry();
            removed++;
        }
        if (removed > 0) {
            System.out.println("🧹 清理旧数据: " + removed + " 条, 当前缓冲: " + dataBuffer.size() + " 条");
        }
    }

    /**
     * 定期打印状态
     */
    private void printStatus() {
        long runningMinutes = (System.currentTimeMillis() - startTime) / 60000;
        IEthTradeRecordService.TradeStats stats = tradeRecordService.getStats();

        System.out.println("\n┌──────────────────────────────────────────────┐");
        System.out.println("│           实时交易状态 (" + runningMinutes + "分钟)            │");
        System.out.println("├──────────────────────────────────────────────┤");
        System.out.println("│ 数据缓冲: " + String.format("%-6d", dataBuffer.size()) + "条                     │");
        System.out.println("│ 已开单:   " + String.format("%-6d", stats.totalTrades) + "单 (空:" + shortTrades.get() + " 多:" + longTrades.get() + ")        │");
        System.out.println("│ 待结算:   " + String.format("%-6d", stats.pendingTrades) + "单                           │");
        System.out.println("│ 已结算:   " + String.format("%-6d", settledCount.get()) + "单 (盈:" + winCount.get() + " 亏:" + loseCount.get() + " 超时:" + timeoutCount.get() + ") │");
        System.out.println("├──────────────────────────────────────────────┤");
        if (settledCount.get() > 0) {
            System.out.println("│ 胜率:     " + String.format("%.2f%%", stats.winRate) + "                        │");
            System.out.println("│ 固定收益: " + String.format("%+.2f", (winCount.get() * 4.0 - loseCount.get() * 5.0)) + "U                       │");
        }
        System.out.println("└──────────────────────────────────────────────┘\n");
    }

    /**
     * 打印最终报告
     */
    private void printFinalReport() {
        IEthTradeRecordService.TradeStats stats = tradeRecordService.getStats();
        long runningMinutes = (System.currentTimeMillis() - startTime) / 60000;

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║              最终交易报告                    ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ 运行时长: " + String.format("%d小时%d分钟", runningMinutes / 60, runningMinutes % 60));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ 总交易数: " + stats.totalTrades);
        System.out.println("║  ├─ 空单: " + shortTrades.get());
        System.out.println("║  └─ 多单: " + longTrades.get());
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ 已结算: " + settledCount.get());
        System.out.println("║  ├─ 盈利: " + winCount.get());
        System.out.println("║  ├─ 亏损: " + loseCount.get());
        System.out.println("║  └─ 超时: " + timeoutCount.get());
        System.out.println("║ 待结算: " + stats.pendingTrades);
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ 胜率: " + String.format("%.2f%%", stats.winRate));
        System.out.println("║ 固定收益(盈利+4U,亏损-5U): " +
                String.format("%+.2fU", (winCount.get() * 4.0 - loseCount.get() * 5.0)));
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("\n所有交易记录已保存到数据库表 eth_trade_record");
    }

    private String formatTimestamp(long timestamp) {
        long ts = timestamp > 1000000000000000L ? timestamp / 1000 : timestamp;
        return SDF.format(new Date(ts));
    }
}