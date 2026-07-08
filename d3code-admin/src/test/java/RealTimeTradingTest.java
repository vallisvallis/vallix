package com.alphay.boot.web.test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.api.domain.EthTradeRecord;
import com.alphay.boot.bpm.service.impl.IEthTradeRecordService;
import com.alphay.boot.bpm.service.impl.IEthKlineSecondService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestTemplate;

import javax.websocket.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时交易测试 - WebSocket 连接币安，实时计算并下单
 *
 * 策略核心逻辑（v11/v12/v13 回踩确认策略）：
 * 1. 连接币安 WebSocket 获取 ETH/USDT 1秒 K线实时数据
 * 2. 维护过去 4 小时的数据缓冲
 * 3. ER效率比率判断市场状态：震荡市(ER<0.20)才开单，单边市(ER>0.65)不开单
 * 4. 时段过滤：仅在北京时间高胜率时段(00-01,06-07,12-13,15,19,22-23)交易
 * 5. 在指定窗口内找到最高价/最低价（极值）
 * 6. 极值出现后，等待价格回踩确认（反弹阈值）再开单，突破则取消
 * 7. 开单后存入数据库，到达结算时间后自动结算
 * 8. 运行一整夜，第二天早上查看结果
 *
 * 当前版本配置：
 * - v11: 25m极值窗口 / 10分钟结算 / 0.05%回踩阈值 / 胜利+4U / 失败-5U
 * - v12: 40m极值窗口 / 30分钟结算 / 0.01%回踩阈值 / 胜利+4.25U / 失败-5U
 * - v13: 40m极值窗口 /  1小时结算 / 0.01%回踩阈值 / 胜利+4.25U / 失败-5U
 *
 * 包含功能：
 * - testPersistence(): 持久化验证（CRUD测试）
 * - testReplayWithCooling(): 冷静期回放对比
 * - testRealTimeTrading(): 主入口 - 实时交易（仅v11/v12/v13）
 * - testDownloadAndReplay(): 下载数据 + 策略回放
 * - testAnalyzeLosingTrades(): 亏损分析 v2
 * - testAnalyzeLosingTradesV3(): 亏损全维度分析 v3
 * - testSettlePendingTrades(): 批量结算待结算交易
 *
 * @author d3code
 */
@Slf4j
@SpringBootTest
public class RealTimeTradingTest {

    // ==================== 依赖注入 ====================

    @Autowired
    private IEthTradeRecordService tradeRecordService;

    @Autowired
    private IEthKlineSecondService ethKlineSecondService;

    // ==================== K线数据写入缓冲 ====================

    /** 数据库时间戳格式是否为微秒 */
    private boolean dbIsMicro = false;
    /** K线写入缓冲队列（批量刷入DB）*/
    private final List<EthKlineSecond> klineWriteBuffer = Collections.synchronizedList(new ArrayList<>());
    /** 已写入时间戳集合（去重）*/
    private final Set<Long> writtenTimestamps = ConcurrentHashMap.newKeySet();
    /** 批量写入阈值：缓冲达到500条即刷新 */
    private static final int KLINE_WRITE_BATCH = 500;
    /** 已写入时间戳过期时间：30分钟 */
    private static final long WRITTEN_TS_TTL = 30 * 60_000_000L;
    /** 上次清理过期时间戳的时间 */
    private long lastWrittenTsCleanup = 0;

    // ==================== 时间常量（微秒）====================

    private static final long ONE_MINUTE_US = 60_000_000L;         // 1分钟
    private static final long THREE_MINUTES_US = 180_000_000L;     // 3分钟
    private static final long FIVE_MINUTES_US = 300_000_000L;      // 5分钟
    private static final long TEN_MINUTES_US = 600_000_000L;       // 10分钟
    private static final long FIFTEEN_MINUTES_US = 900_000_000L;   // 15分钟
    private static final long TWENTY_MINUTES_US = 1_200_000_000L;  // 20分钟
    private static final long THIRTY_MINUTES_US = 1_800_000_000L;  // 30分钟
    private static final long ONE_HOUR_US = 3_600_000_000L;        // 1小时
    private static final long TWO_HOURS_US = 7_200_000_000L;       // 2小时
    private static final long FOUR_HOURS_US = 14_400_000_000L;     // 4小时
    private static final long BOUNCE_WINDOW_US = 120_000_000L;        // 回踩确认窗口(120秒)

    // ==================== ER震荡判市常量 ====================
    private static final int ER_LOOKBACK = 300;          // ER回顾窗口(秒)
    private static final double ER_RANGING = 0.20;       // 震荡市阈值
    private static final double ER_TRENDING = 0.65;      // 单边市阈值

    // ==================== 高胜率时段(北京时间) ====================
    private static final Set<Integer> ALLOWED_HOURS = new HashSet<>(Arrays.asList(
            0, 1, 6, 7, 12, 13, 15, 19, 22, 23));

    // ==================== 方向常量 ====================

    private static final String DIR_SHORT = "空单";
    private static final String DIR_LONG = "多单";

    // ==================== 盈亏规则 ====================

    private static final double WIN_PROFIT_USD = 4.0;
    private static final double LOSE_PROFIT_USD = -5.0;

    /** 计算盈亏：赢+4U，输-5U，超时-5U */
    private static long calcProfit(long wins, long loses, long timeouts) {
        return wins * 4L - loses * 5L - timeouts * 5L;
    }

    // ==================== 策略版本系统（数据驱动，新增版本只需注册） ====================

    /**
     * 策略版本定义：封装单个版本的配置参数和统计计数器
     */
    static class StrategyVersion {
        final String name;              // 版本标识
        final String label;             // 显示标签
        final long windowUs;            // 窗口大小（微秒）
        final long settleUs;            // 结算等待时间（微秒）
        final long gapUs;               // 极值距当前允许的最小时差（微秒）
        final double trendThreshold;    // 趋势过滤阈值，0=无过滤
        final double bounceThreshold;   // 回踩确认阈值，0=无回踩确认
        final double winReturn;         // 胜利收益(U)
        final double loseReturn;        // 亏损收益(U)
        final boolean useBounceConfirm; // 是否启用回踩确认
        final boolean useERFilter;      // 是否启用ER震荡判市
        final boolean useHourFilter;    // 是否启用时段过滤

        final AtomicInteger totalTrades  = new AtomicInteger(0);
        final AtomicInteger longTrades   = new AtomicInteger(0);
        final AtomicInteger shortTrades  = new AtomicInteger(0);
        final AtomicInteger settledCount = new AtomicInteger(0);
        final AtomicInteger winCount     = new AtomicInteger(0);
        final AtomicInteger loseCount    = new AtomicInteger(0);
        final AtomicInteger timeoutCount = new AtomicInteger(0);
        final AtomicInteger skippedByTrend = new AtomicInteger(0);

        StrategyVersion(String name, String label, long windowUs, long settleUs, long gapUs, double trendThreshold) {
            this(name, label, windowUs, settleUs, gapUs, trendThreshold, 0, WIN_PROFIT_USD, LOSE_PROFIT_USD, false, false, false);
        }

        StrategyVersion(String name, String label, long windowUs, long settleUs, long gapUs,
                        double trendThreshold, double bounceThreshold, double winReturn, double loseReturn,
                        boolean useBounceConfirm, boolean useERFilter, boolean useHourFilter) {
            this.name = name;
            this.label = label;
            this.windowUs = windowUs;
            this.settleUs = settleUs;
            this.gapUs = gapUs;
            this.trendThreshold = trendThreshold;
            this.bounceThreshold = bounceThreshold;
            this.winReturn = winReturn;
            this.loseReturn = loseReturn;
            this.useBounceConfirm = useBounceConfirm;
            this.useERFilter = useERFilter;
            this.useHourFilter = useHourFilter;
        }

        long calcProfit() { return (long)(winCount.get() * winReturn + loseCount.get() * loseReturn + timeoutCount.get() * loseReturn); }
        double winRate() { int s = settledCount.get(); return s > 0 ? winCount.get() * 100.0 / s : 0; }
    }

    /**
     * 窗口状态：每个窗口大小独立维护冷却/去重状态
     */
    static class WindowState {
        long lastTradeTs = -1;    // 上次开单时间戳（冷却控制）
        long lastShortTs = -1;    // 上次空单极值时间戳（去重）
        long lastLongTs = -1;     // 上次多单极值时间戳（去重）
        volatile boolean firstSatisfied = false;
    }

    /** 所有策略版本列表 */
    private final List<StrategyVersion> versions = new ArrayList<>();
    /** 版本名 -> 版本对象 */
    private final Map<String, StrategyVersion> verByName = new HashMap<>();
    /** 窗口大小 -> 窗口状态 */
    private final Map<Long, WindowState> winStates = new HashMap<>();

    /**
     * 注册策略版本（数据驱动入口，新增策略只需在此添加一行）
     */
    private void registerVersion(String name, String label, long windowUs, long settleUs, long gapUs, double threshold) {
        StrategyVersion v = new StrategyVersion(name, label, windowUs, settleUs, gapUs, threshold);
        versions.add(v);
        verByName.put(name, v);
        winStates.putIfAbsent(windowUs, new WindowState());
    }

    private void registerVersion(String name, String label, long windowUs, long settleUs, long gapUs,
                                 double trendThreshold, double bounceThreshold, double winReturn, double loseReturn,
                                 boolean useBounceConfirm, boolean useERFilter, boolean useHourFilter) {
        StrategyVersion v = new StrategyVersion(name, label, windowUs, settleUs, gapUs,
                trendThreshold, bounceThreshold, winReturn, loseReturn,
                useBounceConfirm, useERFilter, useHourFilter);
        versions.add(v);
        verByName.put(name, v);
        winStates.putIfAbsent(windowUs, new WindowState());
    }

    /**
     * 初始化策略版本（仅 v11/v12/v13 回踩确认策略）
     * v11: 25m极值/10m结算/0.05%回踩 → +4U/-5U
     * v12: 40m极值/30m结算/0.01%回踩 → +4.25U/-5U
     * v13: 40m极值/1h结算/0.01%回踩 → +4.25U/-5U
     */
    private void initVersions() {
        registerVersion("v11",  "v11(25m/10m/0.05%)", 25 * ONE_MINUTE_US, TEN_MINUTES_US,    0, 0, 0.0005, 4.0,  -5.0, true, true, true);
        registerVersion("v12",  "v12(40m/30m/0.01%)", 40 * ONE_MINUTE_US, THIRTY_MINUTES_US, 0, 0, 0.0001, 4.25, -5.0, true, true, true);
        registerVersion("v13",  "v13(40m/1h/0.01%)",  40 * ONE_MINUTE_US, ONE_HOUR_US,       0, 0, 0.0001, 4.25, -5.0, true, true, true);
    }

    // ==================== 全局汇总（动态计算所有版本合计） ====================

    private int totalTrades()  { return versions.stream().mapToInt(v -> v.totalTrades.get()).sum(); }
    private int shortTrades()  { return versions.stream().mapToInt(v -> v.shortTrades.get()).sum(); }
    private int longTrades()   { return versions.stream().mapToInt(v -> v.longTrades.get()).sum(); }
    private int settledCount() { return versions.stream().mapToInt(v -> v.settledCount.get()).sum(); }
    private int winCount()     { return versions.stream().mapToInt(v -> v.winCount.get()).sum(); }
    private int loseCount()    { return versions.stream().mapToInt(v -> v.loseCount.get()).sum(); }
    private int timeoutCount() { return versions.stream().mapToInt(v -> v.timeoutCount.get()).sum(); }
    private long totalProfit() { return versions.stream().mapToLong(StrategyVersion::calcProfit).sum(); }

    // ==================== 实时数据缓冲 ====================

    /** 1秒K线数据缓冲池（按时间戳排序，线程安全）*/
    private final ConcurrentSkipListMap<Long, KlineData> dataBuffer = new ConcurrentSkipListMap<>();

    // ==================== 待结算交易队列 ====================

    /** 待结算交易映射表（dbId -> PendingTrade）*/
    private final ConcurrentHashMap<Long, PendingTrade> pendingTrades = new ConcurrentHashMap<>();

    // ==================== ER计算与回踩确认 ====================
    private double erSum = 0;
    private int erCount = 0;
    private final double[] erRing = new double[ER_LOOKBACK];
    private int erRingIdx = 0;
    private boolean erRingFull = false;
    private double prevClose = -1;

    private static class BouncePending {
        long extremeTs;
        double extremePrice;
        boolean isHighExtreme;
        KlineData maxData;
        KlineData minData;
        StrategyVersion ver;
    }
    private final ConcurrentHashMap<Long, BouncePending> bouncePending = new ConcurrentHashMap<>();

    // ==================== 策略诊断状态 ====================

    private final AtomicInteger klineReceived = new AtomicInteger(0);
    private final AtomicInteger strategyCalls = new AtomicInteger(0);
    private final AtomicInteger strategyRejected = new AtomicInteger(0);
    private final AtomicInteger rejectShortGap = new AtomicInteger(0);
    private final AtomicInteger rejectLongGap = new AtomicInteger(0);
    private final AtomicInteger rejectDedup = new AtomicInteger(0);
    private final AtomicInteger rejectCooling = new AtomicInteger(0);
    private final AtomicInteger rejectNoBounce = new AtomicInteger(0);
    private final AtomicInteger rejectBreakout = new AtomicInteger(0);
    private final AtomicInteger rejectTrending = new AtomicInteger(0);
    private final AtomicInteger rejectNotRanging = new AtomicInteger(0);
    private final AtomicInteger rejectHourFilter = new AtomicInteger(0);
    private volatile String lastDiag = "";
    private volatile String lastDetailDiag = "";
    private final AtomicInteger klineParseError = new AtomicInteger(0);
    private final AtomicInteger rawMessageCount = new AtomicInteger(0);
    private final AtomicInteger skippedUnclosed = new AtomicInteger(0);
    private volatile long lastRawMessageAt = 0;
    private volatile long lastKlineDataAt = 0;
    private volatile boolean dataHealthy = false;
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveMissCount = new AtomicInteger(0);
    private final Object klineFlushLock = new Object();
    private long lastCleanupDiag = 0;

    // ==================== 运行控制 ====================

    /** 静态自引用，供 WebSocket 端点回调 */
    private static RealTimeTradingTest self;
    /** 关闭信号量：主线程等待 */
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    /** 运行标志 */
    private volatile boolean running = true;
    /** 币安 WebSocket 会话 */
    private volatile Session binanceSession;
    /** 启动时间 */
    private long startTime;

    /** 时间格式化器 */
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // ==================== 文件日志系统 ====================

    /** 日志文件目录 */
    private static final String LOG_DIR = "D:/vallix/d3code/logs";
    /** 日志写入器 */
    private PrintWriter logWriter;
    /** 日志时间格式化器 */
    private static final DateTimeFormatter LOG_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 初始化日志文件
     */
    private void initLog() {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.exists()) dir.mkdirs();
            String fileName = LOG_DIR + "/realtime_trading_" + LocalDate.now() + ".log";
            logWriter = new PrintWriter(new FileWriter(fileName, true), true);
            log("INFO", "系统", "日志文件初始化完成: " + fileName);
        } catch (Exception e) {
            System.err.println("[日志] 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 写入日志（同时输出到控制台和文件）
     */
    private void log(String level, String module, String msg) {
        String line = String.format("[%s] [%s] [%s] %s",
                LocalDateTime.now().format(LOG_TS_FMT), level, module, msg);
        System.out.println(line);
        if (logWriter != null) {
            logWriter.println(line);
        }
    }

    /**
     * 关闭日志文件
     */
    private void closeLog() {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
    }

    /**
     * 包装定时任务，捕获异常防止任务静默停止
     */
    private Runnable wrapTask(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable e) {
                log("ERROR", "系统", "定时任务[" + name + "]异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                System.err.println("❌ 定时任务[" + name + "]异常: " + e.getMessage());
            }
        };
    }

    /**
     * 健康检查：检测 WebSocket 数据是否中断
     * 每30秒执行一次，如果超过60秒无数据则主动断开重连
     */
    private void healthCheck() {
        long now = System.currentTimeMillis();
        long silentMs = (lastRawMessageAt == 0) ? 0 : (now - lastRawMessageAt);

        if (lastRawMessageAt > 0 && silentMs > 60_000) {
            log("WARN", "健康", String.format("WebSocket 数据中断 %.0f 秒，主动断开重连...", silentMs / 1000.0));
            System.out.println("⚠️ WebSocket 数据中断 " + (silentMs / 1000) + " 秒，主动断开重连...");
            dataHealthy = false;

            // 主动关闭旧连接
            Session oldSession = binanceSession;
            binanceSession = null;
            if (oldSession != null) {
                try { oldSession.close(); } catch (Exception ignored) {}
            }

            // 异步重连
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    log("INFO", "网络", "健康检查触发重连...");
                    connectBinanceWebSocket();
                    reconnectCount.incrementAndGet();
                    log("INFO", "网络", "健康检查重连成功 (重连次数=" + reconnectCount.get() + ")");
                    System.out.println("✅ 健康检查重连成功");
                } catch (Throwable e) {
                    log("ERROR", "网络", "健康检查重连失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            }, "ws-health-reconnect").start();
        }
    }

    /**
     * 启动时结算数据库中所有待结算记录
     * 从币安API拉取到期时刻的K线数据，判断盈亏并更新数据库
     * 未到结算时间的跳过，API无数据的标记为超时
     */
    private void settleAllPendingTrades() {
        try {
            List<EthTradeRecord> pending = tradeRecordService.list(
                    new LambdaQueryWrapper<EthTradeRecord>()
                            .eq(EthTradeRecord::getDeleted, 0)
                            .eq(EthTradeRecord::getStatus, "待结算"));

            if (pending == null || pending.isEmpty()) {
                log("INFO", "系统", "无待结算记录，跳过");
                System.out.println("✅ 无待结算记录");
                return;
            }

            log("INFO", "系统", "发现 " + pending.size() + " 条待结算记录，开始逐条结算...");
            System.out.println("🔍 发现 " + pending.size() + " 条待结算记录，开始结算...\n");

            RestTemplate restTemplate = new RestTemplate();
            long nowUs = System.currentTimeMillis() * 1000L;
            int settled = 0, skipped = 0, timeout = 0;
            double totalProfit = 0;

            for (int i = 0; i < pending.size(); i++) {
                EthTradeRecord r = pending.get(i);
                boolean isShort = "空单".equals(r.getDirection());
                long openTsUs = r.getOpenTimestamp();
                double openPrice = Double.parseDouble(r.getOpenPrice());

                // 根据策略版本确定结算时间
                String verName = r.getStrategyVersion() != null ? r.getStrategyVersion() : "base";
                StrategyVersion ver = verByName.get(verName);
                long settleUs = (ver != null) ? ver.settleUs : TEN_MINUTES_US;
                long targetTsUs = openTsUs + settleUs;

                if (targetTsUs > nowUs) {
                    skipped++;
                    System.out.println(String.format("  [%d/%d] ⏳ %s %s 结算时间未到 (%s)，跳过",
                            i + 1, pending.size(), verName, isShort ? "空" : "多",
                            formatTimestamp(targetTsUs)));
                    continue;
                }

                // 从币安API拉取结算时刻的K线数据
                try {
                    Thread.sleep(100);
                    long settleMs = targetTsUs / 1000;
                    String url = String.format(
                            "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=1",
                            settleMs, settleMs + 1000);
                    Object[][] klines = restTemplate.getForObject(url, Object[][].class);

                    if (klines != null && klines.length > 0) {
                        double settlePrice = Double.parseDouble(klines[0][4].toString());
                        boolean isWin = isShort
                                ? settlePrice < openPrice
                                : settlePrice > openPrice;
                        StrategyVersion stv = verByName.get(r.getStrategyVersion());
                        double winR = (stv != null) ? stv.winReturn : WIN_PROFIT_USD;
                        double loseR = (stv != null) ? stv.loseReturn : LOSE_PROFIT_USD;
                        double profit = isWin ? winR : loseR;
                        double profitPercent = (profit / openPrice) * 100;
                        String status = isWin ? "盈利" : "亏损";

                        tradeRecordService.settleTrade(r.getId(), targetTsUs,
                                String.format("%.8f", settlePrice),
                                String.format("%.8f", profit),
                                String.format("%.2f", profitPercent),
                                status);

                        if (ver != null) {
                            ver.settledCount.incrementAndGet();
                            if (isWin) ver.winCount.incrementAndGet();
                            else ver.loseCount.incrementAndGet();
                        }

                        totalProfit += profit;
                        settled++;

                        log("INFO", "启动结算", String.format("[%s] %s id=%d 开=%.2f 平=%.2f 盈亏=%+.0fU %s",
                                verName, r.getDirection(), r.getId(),
                                openPrice, settlePrice, profit, status));
                        System.out.println(String.format("  [%d/%d] %s %s %s 开:$%.4f 平:$%.4f 盈亏:%+.0fU",
                                i + 1, pending.size(), status, verName,
                                r.getDirection(), openPrice, settlePrice, profit));
                    } else {
                        // API无数据，标记为超时
                        double profit = LOSE_PROFIT_USD;
                        tradeRecordService.settleTrade(r.getId(), targetTsUs,
                                "0", String.format("%.8f", profit), "0", "超时(启动结算)");

                        if (ver != null) ver.timeoutCount.incrementAndGet();
                        totalProfit += profit;
                        timeout++;

                        log("WARN", "启动结算", String.format("[%s] %s id=%d 超时 目标=%s API无数据",
                                verName, r.getDirection(), r.getId(),
                                formatTimestamp(targetTsUs)));
                        System.out.println(String.format("  [%d/%d] ⚠️ %s %s 超时结算 API无数据 盈亏:%+.0fU",
                                i + 1, pending.size(), verName, r.getDirection(), profit));
                    }
                } catch (Exception e) {
                    timeout++;
                    double profit = LOSE_PROFIT_USD;
                    tradeRecordService.settleTrade(r.getId(), targetTsUs,
                            "0", String.format("%.8f", profit), "0", "超时(启动异常)");

                    if (ver != null) ver.timeoutCount.incrementAndGet();
                    totalProfit += profit;

                    log("ERROR", "启动结算", String.format("[%s] %s id=%d 结算异常: %s",
                            verName, r.getDirection(), r.getId(), e.getMessage()));
                    System.out.println(String.format("  [%d/%d] ❌ %s %s 结算异常: %s",
                            i + 1, pending.size(), verName, r.getDirection(), e.getMessage()));
                }
            }

            String summary = String.format("启动结算完成: 总计%d条 已结算%d 跳过%d 超时%d 盈亏%+.0fU",
                    pending.size(), settled, skipped, timeout, totalProfit);
            log("INFO", "系统", summary);
            System.out.println("\n" + summary + "\n");

        } catch (Exception e) {
            log("ERROR", "系统", "启动结算异常: " + e.getMessage());
            System.err.println("❌ 启动结算异常: " + e.getMessage());
        }
    }


    // ==================== 内部数据模型 ====================

    /**
     * K线数据 DTO
     * 存储每秒K线的OHLCV数据，时间戳为微秒
     */
    static class KlineData {
        long timestamp;   // 时间戳（微秒）
        double open;      // 开盘价
        double close;     // 收盘价
        double high;      // 最高价
        double low;       // 最低价
        double volume;    // 成交量
    }

    /**
     * 待结算交易
     * 记录已开单但尚未到期的交易信息
     */
    static class PendingTrade {
        Long dbId;          // 数据库记录ID
        long openTs;        // 开单时间戳（微秒）
        long targetTs;      // 目标结算时间戳（微秒）
        String direction;   // 方向：多单/空单
        String openPrice;   // 开单价格
        String strategyVersion; // 策略版本 v1/v2
    }

    /**
     * K线点（用于回放分析）
     * 不同于KlineData，此数据模型用于离线回放场景
     */
    private static class KlinePoint {
        long ts;
        double open, high, low, close;
        KlinePoint(long ts, double open, double high, double low, double close) {
            this.ts = ts; this.open = open; this.high = high; this.low = low; this.close = close;
        }
    }

    /**
     * 回放交易结果
     */
    private static class ReplayTrade {
        long openTs;          // 开单时间
        double openPrice;     // 开单价
        double settlePrice;   // 结算价
        double profit;        // 盈亏
        boolean passed;       // 是否通过冷静期
        boolean win;          // 是否盈利
        int coolingSecs;      // 冷静期等待秒数
    }

    /**
     * 亏损详情（用于V3全维度分析）
     */
    static class LossDetail {
        String time;           // 开单时间
        String dir;            // 方向
        String lossType;       // 亏损类型
        String trendLabel;     // 趋势标签
        double openPrice;      // 开单价
        double loss;           // 亏损额
        double mfe;            // 最大浮盈
        double mae;            // 最大浮亏
        double trendChange;    // 趋势变化率
        long lossTime;         // 开始亏损时间
        boolean recovered;     // 是否恢复
    }

    /**
     * 亏损簇（连续亏损归为一组）
     */
    static class LossCluster {
        String direction;
        int count;
        long firstOpenMs, lastOpenMs;
        double totalLoss, bestMfe;
        List<String> types;

        LossCluster(String d, long t) {
            this.direction = d;
            this.firstOpenMs = t;
            this.lastOpenMs = t;
            this.count = 0;
            this.totalLoss = 0;
            this.bestMfe = 0;
            this.types = new ArrayList<>();
        }

        void add(long ts, double loss, double mfe, String type) {
            count++;
            lastOpenMs = ts;
            totalLoss += loss;
            if (mfe > bestMfe) bestMfe = mfe;
            types.add(type);
        }
    }

    // ==================== 测试方法 ====================

    /**
     * 持久化验证测试
     * 验证 eth_trade_record 表的 CRUD 操作是否正常
     * 包括：创建记录、回查记录、结算记录、统计查询、清理测试数据
     */
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

    /**
     * 回放昨天所有开单，加入1分钟冷静期后重新判断
     * 冷静期规则：满足开单条件后，等待1分钟，若价格不再突破极值才开单
     *   - 空单：冷静期内没有出现比原极值更高的价格 → 开单
     *   - 多单：冷静期内没有出现比原极值更低的价格 → 开单
     * 结算：冷静期结束开单，10分钟后平仓
     */
    @Test
    public void testReplayWithCooling() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     全部开单回放 - 加1分钟冷静期后对比       ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("新规则: 满足开单条件后，等待1分钟确认价格不再突破极值再开");
        System.out.println("  ├─ 空单：冷静期内没有出现比原极值更高的价格才开");
        System.out.println("  └─ 多单：冷静期内没有出现比原极值更低的价格才开");
        System.out.println();

        RestTemplate restTemplate = new RestTemplate();

        // 查询表中所有交易记录
        List<EthTradeRecord> records = tradeRecordService.list(
                new LambdaQueryWrapper<EthTradeRecord>()
                        .eq(EthTradeRecord::getDeleted, 0));

        if (records == null || records.isEmpty()) {
            System.out.println("⚠️ 没有找到昨天的交易记录");
            return;
        }

        System.out.println("📊 共找到 " + records.size() + " 条昨天交易记录\n");

        int originalWin = 0, originalLose = 0, originalTimeout = 0;
        int newWin = 0, newLose = 0, newSkip = 0;
        double originalTotalProfit = 0, newTotalProfit = 0;
        int processed = 0;

        for (EthTradeRecord record : records) {
            processed++;
            boolean isShort = "空单".equals(record.getDirection());
            long openTs = record.getOpenTimestamp();
            long openTsMs = openTs / 1000;
            double openPrice = Double.parseDouble(record.getOpenPrice());

            // 原始结果统计
            double origProfit = record.getProfit() != null ? Double.parseDouble(record.getProfit()) : 0;
            if ("盈利".equals(record.getStatus())) originalWin++;
            else if ("亏损".equals(record.getStatus())) originalLose++;
            else originalTimeout++;
            originalTotalProfit += origProfit;

            System.out.println(String.format("[%d/%d] %s 开单:%s $%.4f | 结算:%s $%s | 原始: %s(%.4f)",
                    processed, records.size(),
                    isShort ? "空" : "多",
                    formatTimestamp(openTs), openPrice,
                    record.getCloseTimestamp() != null ? formatTimestamp(record.getCloseTimestamp()) : "无",
                    record.getClosePrice() != null ? record.getClosePrice() : "无",
                    record.getStatus(), origProfit));

            try {
                // 1. 拉取开单前20分钟的1s数据，找到窗口极值
                long windowStart = openTsMs - 20 * 60 * 1000;
                String url = String.format(
                        "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=1200",
                        windowStart, openTsMs);
                Object[][] windowKlines = restTemplate.getForObject(url, Object[][].class);
                if (windowKlines == null || windowKlines.length == 0) {
                    System.out.println("  ⚠️ 无法获取20分钟窗口数据，跳过");
                    continue;
                }

                double extremePrice = isShort ? Double.MIN_VALUE : Double.MAX_VALUE;
                for (Object[] k : windowKlines) {
                    double price = isShort ? Double.parseDouble(k[2].toString()) : Double.parseDouble(k[3].toString());
                    if (isShort && price > extremePrice) extremePrice = price;
                    if (!isShort && price < extremePrice) extremePrice = price;
                }

                // 2. 拉取冷静期数据（开单后最多等15分钟，找1分钟无突破窗口）
                Thread.sleep(200);
                long coolEnd = openTsMs + 15 * 60 * 1000;
                String coolUrl = String.format(
                        "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=900",
                        openTsMs, coolEnd);
                Object[][] coolKlines = restTemplate.getForObject(coolUrl, Object[][].class);
                if (coolKlines == null || coolKlines.length == 0) {
                    System.out.println("  ⚠️ 无法获取冷静期数据，跳过");
                    continue;
                }

                // 3. 扫描找第一个完整1分钟无突破的窗口
                double currentExtreme = extremePrice;
                long lastBreakMs = openTsMs - 1;
                long openTimeMs = -1;
                double openAtPrice = 0;
                int breakCount = 0;

                for (Object[] k : coolKlines) {
                    long t = Long.parseLong(k[0].toString());
                    double high = Double.parseDouble(k[2].toString());
                    double low = Double.parseDouble(k[3].toString());
                    double close = Double.parseDouble(k[4].toString());
                    boolean broken = (isShort && high > currentExtreme) || (!isShort && low < currentExtreme);

                    if (broken) {
                        breakCount++;
                        lastBreakMs = t;
                        currentExtreme = isShort ? high : low;
                    }

                    if (t - lastBreakMs >= 61 * 1000) {
                        openTimeMs = t;
                        openAtPrice = close;
                        break;
                    }
                }

                if (openTimeMs < 0) {
                    newSkip++;
                    System.out.println(String.format(
                            "  ❌ 15分钟内始终在突破(共%d次)，无法找到1分钟冷静窗口 → 取消开单", breakCount));
                    continue;
                }

                // 4. 找到冷静窗口，开单
                double delayedOpenPrice = openAtPrice;

                // 5. 拉取延迟开单后10分钟的结算数据
                Thread.sleep(200);
                long delayedOpenMs = openTimeMs;
                long settleTime = delayedOpenMs + 10 * 60 * 1000;
                String settleUrl = String.format(
                        "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=1",
                        settleTime, settleTime + 1000);
                Object[][] settleKlines = restTemplate.getForObject(settleUrl, Object[][].class);

                String newStatus;
                double newProfit;
                double settlePrice = 0;
                if (settleKlines == null || settleKlines.length == 0) {
                    newStatus = "超时";
                    newProfit = 0;
                } else {
                    settlePrice = Double.parseDouble(settleKlines[0][4].toString());
                    newProfit = isShort ? (delayedOpenPrice - settlePrice) : (settlePrice - delayedOpenPrice);
                    newStatus = newProfit > 0 ? "盈利" : "亏损";
                }

                if ("盈利".equals(newStatus)) newWin++;
                else if ("亏损".equals(newStatus)) newLose++;
                else newSkip++;
                newTotalProfit += newProfit;

                System.out.println(String.format("  ✅ 冷静期通过 → 开单:%s $%.4f | 结算:%s $%.4f | 收益=%.4f %s",
                        formatTimestamp(openTimeMs * 1000L), delayedOpenPrice,
                        formatTimestamp(settleTime * 1000L), settlePrice,
                        newProfit, newStatus));

            } catch (Exception e) {
                System.out.println("  ❌ 处理异常: " + e.getMessage());
            }
        }

        // 打印对比报告
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║              冷静期回测对比报告              ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ 原始策略:                                    ║");
        System.out.println(String.format("║   盈利: %-4d 亏损: %-4d 超时: %-4d       ║", originalWin, originalLose, originalTimeout));
        System.out.println(String.format("║   总收益: %+15.4f                        ║", originalTotalProfit));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ 新策略(加1分钟冷静期):                       ║");
        System.out.println(String.format("║   盈利: %-4d 亏损: %-4d 跳过: %-4d       ║", newWin, newLose, newSkip));
        System.out.println(String.format("║   总收益: %+15.4f                        ║", newTotalProfit));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 收益变化: %+15.4f                        ║", newTotalProfit - originalTotalProfit));
        System.out.println("╚══════════════════════════════════════════════╝");
    }

    /**
     * 实时交易主入口（仅运行 v11/v12/v13）
     * 启动 WebSocket 连接币安，实时接收1秒K线并执行回踩确认策略
     * 核心逻辑：ER震荡判市 + 时段过滤 + 极值检测 + 回踩确认开单 + 自动结算
     * 包含：数据连续性检查、定时结算、状态打印、数据清理、K线批量写入
     * 按 Ctrl+C 停止运行
     */
    @Test
    public void testRealTimeTrading() throws Exception {
        initLog();
        initVersions();

        log("INFO", "系统", "========== ETH/USDT 实时交易策略启动 ==========");
        log("INFO", "系统", "策略规则: 仅运行 v11/v12/v13 三版本并行，回踩确认+ER震荡+时段过滤");
        log("INFO", "系统", "v11: 25m极值/10m结算/0.05%回踩 → +4U/-5U");
        log("INFO", "系统", "v12: 40m极值/30m结算/0.01%回踩 → +4.25U/-5U");
        log("INFO", "系统", "v13: 40m极值/1h结算/0.01%回踩 → +4.25U/-5U");
        log("INFO", "系统", "数据缓冲: 4小时 清理: 每小时清理4小时前数据");

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     ETH/USDT 实时交易策略 - WebSocket 版     ║");
        System.out.println("║     运行一整夜，自动开单 + 自动结算           ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("策略规则:");
        System.out.println("  ├─ 数据源: 币安 WebSocket 1秒K线 (data-stream.binance.vision)");
        System.out.println("  ├─ 数据缓冲: 4小时  清理: 每小时清理4小时前数据");
        System.out.println("  ├─ 核心逻辑: ER震荡判市(ER<0.20) + 时段过滤(10个高胜率时段)");
        System.out.println("  ├─ 开单规则: 极值出现后等待回踩确认，突破取消");
        System.out.println("  ├─ 版本配置:");
        System.out.println("  │  v11: 25m极值窗口 / 10分钟结算 / 0.05%回踩阈值 / 胜利+4U / 失败-5U");
        System.out.println("  │  v12: 40m极值窗口 / 30分钟结算 / 0.01%回踩阈值 / 胜利+4.25U / 失败-5U");
        System.out.println("  │  v13: 40m极值窗口 /  1小时结算 / 0.01%回踩阈值 / 胜利+4.25U / 失败-5U");
        System.out.println("  └─ 存储: 实时写入 eth_trade_record 表");
        System.out.println();

        startTime = System.currentTimeMillis();
        self = this;

        // 启动前确保数据库K线数据连续，为回测提供数据支持
        log("INFO", "系统", "开始检查数据库K线数据连续性...");
        ensureDataContinuity();
        log("INFO", "系统", "K线数据连续性检查完成");

        // 数据加载完毕后，结算数据库中所有未结算的开单
        log("INFO", "系统", "开始结算数据库中所有待结算记录...");
        settleAllPendingTrades();
        log("INFO", "系统", "待结算记录全部处理完毕");

        // 连接币安 WebSocket
        log("INFO", "网络", "开始连接币安 WebSocket...");
        connectBinanceWebSocket();

        // 启动定时任务：结算检查 + 状态打印 + 数据清理 + K线刷新 + 健康检查
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
        scheduler.scheduleAtFixedRate(wrapTask("结算检查", this::checkPendingSettlements), 10, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(wrapTask("状态打印", this::printStatus), 60, 300, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(wrapTask("数据清理", this::cleanupOldData), 120, 3600, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(wrapTask("K线刷库", this::flushKlineBuffer), 30, 30, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(wrapTask("健康检查", this::healthCheck), 30, 30, TimeUnit.SECONDS);
        log("INFO", "系统", "定时任务已启动: 结算(1s) 状态(5min) 清理(1h) K线刷库(30s) 健康检查(30s)");

        // 添加 JVM 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("WARN", "系统", "收到关闭信号，正在停止...");
            System.out.println("\n🛑 正在关闭...");
            running = false;
            flushKlineBuffer();
            printFinalReport();
            closeLog();
            shutdownLatch.countDown();
        }));

        // 主线程阻塞，保持运行
        log("INFO", "系统", "实时交易系统已启动，开始监控...");
        System.out.println("✅ 实时交易系统已启动，开始监控...");
        System.out.println("   按 Ctrl+C 停止运行\n");
        shutdownLatch.await();
    }

    /**
     * 连接币安 WebSocket 1秒K线流
     */
    private void connectBinanceWebSocket() throws Exception {
        String wsUrl = "wss://data-stream.binance.vision/ws/ethusdt@kline_1s";
        log("INFO", "网络", "正在连接币安 WebSocket: " + wsUrl);
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
            log("ERROR", "网络", "连接币安 WebSocket 超时(30s)");
            throw new RuntimeException("连接币安 WebSocket 超时");
        }
        log("INFO", "网络", "币安 WebSocket 连接建立成功");
    }

    /**
     * 币安 WebSocket 客户端端点
     */
    @ClientEndpoint
    public static class BinanceClientEndpoint {

        @OnOpen
        public void onOpen(Session session) {
            self.binanceSession = session;
            self.log("INFO", "网络", "WebSocket 已连接: session=" + session.getId());
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
            self.log("WARN", "网络", "WebSocket 断开: " + reason.getReasonPhrase() + " (code=" + reason.getCloseCode() + ")");
            System.out.println("⚠️ 币安 WebSocket 断开: " + reason.getReasonPhrase());
            self.binanceSession = null;
            self.dataHealthy = false;
            if (self.running) {
                new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                        self.log("INFO", "网络", "尝试重连...");
                        System.out.println("🔄 尝试重连...");
                        self.connectBinanceWebSocket();
                        self.log("INFO", "网络", "重连成功");
                    } catch (Throwable e) {
                        self.log("ERROR", "网络", "重连失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        log.error("重连失败", e);
                    }
                }, "ws-reconnect").start();
            }
        }

        @OnError
        public void onError(Session session, Throwable thr) {
            self.log("ERROR", "网络", "WebSocket 错误: " + thr.getMessage());
            System.err.println("❌ 币安 WebSocket 错误: " + thr.getMessage());
            self.binanceSession = null;
            self.dataHealthy = false;
            if (self.running) {
                new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                        self.log("INFO", "网络", "错误后尝试重连...");
                        System.out.println("🔄 错误后尝试重连...");
                        self.connectBinanceWebSocket();
                        self.log("INFO", "网络", "重连成功");
                    } catch (Throwable e) {
                        self.log("ERROR", "网络", "重连失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                }, "ws-reconnect-err").start();
            }
        }
    }

    /**
     * 处理币安 K线消息
     */
    private void handleKlineMessage(String message) {
        rawMessageCount.incrementAndGet();
        lastRawMessageAt = System.currentTimeMillis();

        // 记录第一条消息
        if (rawMessageCount.get() == 1) {
            log("INFO", "数据", "收到第一条WebSocket消息，开始接收K线数据");
        }

        try {
            JSONObject json = JSON.parseObject(message);
            JSONObject kline = json.getJSONObject("k");

            if (kline == null || !kline.getBooleanValue("x")) {
                skippedUnclosed.incrementAndGet();
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
            lastKlineDataAt = System.currentTimeMillis();
            dataHealthy = true;
            consecutiveMissCount.set(0);
            int received = klineReceived.incrementAndGet();

            // 每60条消息打印一次数据速率
            if (received % 60 == 0) {
                long bufFirst = dataBuffer.isEmpty() ? 0 : dataBuffer.firstKey();
                long bufLast = dataBuffer.isEmpty() ? 0 : dataBuffer.lastKey();
                log("DEBUG", "数据", String.format("收到#%d K线 缓冲:%d条 范围:%s~%s (%d分钟)",
                        received, dataBuffer.size(),
                        formatTimestamp(bufFirst), formatTimestamp(bufLast),
                        (bufLast - bufFirst) / 60_000_000L));
                System.out.println("📡 收到#" + received
                        + " 缓冲:" + dataBuffer.size() + "条"
                        + " 范围:" + formatTimestamp(bufFirst) + "~" + formatTimestamp(bufLast)
                        + " (" + (bufLast - bufFirst) / 60_000_000L + "分钟)");
            }

            // 写入K线数据库供回测使用
            writeKlineToDatabase(data);

            // 执行策略
            executeStrategy(data);

        } catch (Throwable e) {
            klineParseError.incrementAndGet();
            log("ERROR", "数据", "K线处理异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            System.err.println("❌ K线处理异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 执行交易策略（数据驱动，按窗口大小分组处理）
     */
    private void executeStrategy(KlineData currentData) {
        int callNum = strategyCalls.incrementAndGet();
        long currentTs = currentData.timestamp;
        double currentPrice = currentData.close;

        // ==================== ER效率比实时更新 ====================
        if (prevClose > 0) {
            double absChange = Math.abs(currentPrice - prevClose);
            erSum += absChange;
            if (erRingFull) {
                erSum -= erRing[erRingIdx];
            }
            erRing[erRingIdx] = absChange;
            erRingIdx++;
            if (erRingIdx >= ER_LOOKBACK) {
                erRingIdx = 0;
                erRingFull = true;
            }
            erCount++;
        }
        prevClose = currentPrice;

        // 数据新鲜度检查：如果缓冲最新数据超过90秒未更新，跳过策略执行
        if (!dataBuffer.isEmpty()) {
            long dataAgeMs = (System.currentTimeMillis() * 1000 - dataBuffer.lastKey()) / 1000;
            if (dataAgeMs > 90_000) {
                if (callNum % 60 == 0) {
                    log("WARN", "策略", "数据过期 " + (dataAgeMs / 1000) + " 秒，跳过策略执行");
                }
                return;
            }
        }

        // 按窗口大小分组，每组只计算一次极值
        Set<Long> processedWindows = new HashSet<>();
        for (StrategyVersion ver : versions) {
            long windowUs = ver.windowUs;
            if (processedWindows.contains(windowUs)) continue;
            processedWindows.add(windowUs);

            WindowState ws = winStates.get(windowUs);
            long minWindowUs = windowUs - ONE_MINUTE_US;

            ExtremaResult ext = findExtrema(currentTs, currentPrice, windowUs, minWindowUs, ws);

            if (ext == null) continue;

            // 处理该窗口下的所有版本
            for (StrategyVersion ver2 : versions) {
                if (ver2.windowUs != windowUs) continue;
                processVersion(currentTs, currentPrice, ext, ver2, ws);
            }
        }

        // 检查回踩待确认队列
        checkBouncePending(currentTs, currentPrice);

        // 诊断（每60次调用打印一次，覆盖所有窗口）
        if (strategyCalls.get() % 60 == 0) {
            StringBuilder diag = new StringBuilder();
            StringBuilder detail = new StringBuilder();
            for (Long windowUs : winStates.keySet()) {
                WindowState ws = winStates.get(windowUs);
                NavigableMap<Long, KlineData> winData = dataBuffer.subMap(currentTs - windowUs, true, currentTs, false);
                long winRange = winData.isEmpty() ? 0 : (currentTs - winData.firstKey()) / 1_000_000L;
                ExtremaResult ext = findExtrema(currentTs, currentPrice, windowUs, windowUs - ONE_MINUTE_US, null);
                if (ext != null) {
                    long gapMax = currentTs - ext.maxTs;
                    long gapMin = currentTs - ext.minTs;
                    diag.append(String.format("%dm:buf=%ds/%d条 gapS=%.1fs gapL=%.1fs ",
                            windowUs / 60_000_000L, winRange, winData.size(),
                            gapMax / 1_000_000.0, gapMin / 1_000_000.0));
                    detail.append(String.format("%dm:S=%.1f(%s) L=%.1f(%s)|",
                            windowUs / 60_000_000L,
                            gapMax / 1_000_000.0, gapMax >= ONE_MINUTE_US ? "✓" : "✗",
                            gapMin / 1_000_000.0, gapMin >= ONE_MINUTE_US ? "✓" : "✗"));
                }
            }
            lastDiag = diag.toString();
            lastDetailDiag = detail.toString();
        }
    }

    /** 窗口极值结果 */
    static class ExtremaResult {
        KlineData maxData, minData;
        double maxPrice, minPrice;
        long maxTs, minTs;
    }

    /**
     * 在指定窗口内寻找最高价和最低价
     * @param ws 窗口状态，用于首次满足日志（可为null）
     */
    private ExtremaResult findExtrema(long currentTs, double currentPrice, long windowUs, long minWindowUs, WindowState ws) {
        long windowStart = currentTs - windowUs;
        NavigableMap<Long, KlineData> windowData = dataBuffer.subMap(windowStart, true, currentTs, false);

        if (windowData.isEmpty() || (currentTs - windowData.firstKey()) < minWindowUs) {
            return null;
        }

        if (ws != null && !ws.firstSatisfied) {
            ws.firstSatisfied = true;
            log("INFO", "策略", String.format("窗口(%dm)首次满足条件! 缓冲=%d条 窗口=%d条/%ds 当前价=%.2f",
                    windowUs / 60_000_000L, dataBuffer.size(), windowData.size(),
                    (currentTs - windowData.firstKey()) / 1_000_000L, currentPrice));
        }

        ExtremaResult result = new ExtremaResult();
        result.maxPrice = Double.MIN_VALUE;
        result.minPrice = Double.MAX_VALUE;

        for (KlineData kd : windowData.values()) {
            if (kd.high > result.maxPrice) {
                result.maxPrice = kd.high;
                result.maxData = kd;
            }
            if (kd.low < result.minPrice) {
                result.minPrice = kd.low;
                result.minData = kd;
            }
        }

        if (result.maxData == null || result.minData == null) return null;
        result.maxTs = result.maxData.timestamp;
        result.minTs = result.minData.timestamp;
        return result;
    }

    /**
     * 处理单个策略版本的开单逻辑（v11/v12/v13 统一走回踩确认流程）
     */
    private void processVersion(long currentTs, double currentPrice, ExtremaResult ext,
                                StrategyVersion ver, WindowState ws) {
        // 数据不健康时暂停开单，避免在数据中断期间产生无效交易
        if (!dataHealthy) {
            return;
        }

        // 回踩确认版本走独立流程
        if (ver.useBounceConfirm) {
            processBounceVersion(currentTs, currentPrice, ext, ver, ws);
            return;
        }

        long maxTs = ext.maxTs;
        long minTs = ext.minTs;

        // 空单条件：最高价出现 gapUs 后开空
        if (currentTs - maxTs >= ver.gapUs
                && currentTs > maxTs && maxTs != ws.lastShortTs
                && (ws.lastTradeTs == -1 || currentTs - ws.lastTradeTs >= ONE_MINUTE_US)) {

            if (ver.trendThreshold > 0 && isTrendUp(currentPrice, currentTs, ver.trendThreshold)) {
                ver.skippedByTrend.incrementAndGet();
            } else {
                boolean ok = createTrade(ext.maxData, ext.minData, DIR_SHORT, currentTs, currentPrice, ver);
                if (ok) {
                    ws.lastShortTs = maxTs;
                    ws.lastTradeTs = currentTs;
                }
            }
        }

        // 多单条件：最低价出现 gapUs 后开多
        if (currentTs - minTs >= ver.gapUs
                && currentTs > minTs && minTs != ws.lastLongTs
                && (ws.lastTradeTs == -1 || currentTs - ws.lastTradeTs >= ONE_MINUTE_US)) {

            if (ver.trendThreshold > 0 && isTrendDown(currentPrice, currentTs, ver.trendThreshold)) {
                ver.skippedByTrend.incrementAndGet();
            } else {
                boolean ok = createTrade(ext.maxData, ext.minData, DIR_LONG, currentTs, currentPrice, ver);
                if (ok) {
                    ws.lastLongTs = minTs;
                    ws.lastTradeTs = currentTs;
                }
            }
        }
    }

    /**
     * 回踩确认版本处理（v11/v12/v13 专用）
     * 1. ER震荡判市：仅震荡市(ER < 0.20)开单，单边市(ER > 0.65)跳过
     * 2. 时段过滤：仅北京高胜率时段(00-01,06-07,12-13,15,19,22-23)开单
     * 3. 极值检测后将信号加入回踩待确认队列，等待回踩确认或突破取消
     */
    private void processBounceVersion(long currentTs, double currentPrice, ExtremaResult ext,
                                      StrategyVersion ver, WindowState ws) {
        // ER震荡判市
        if (ver.useERFilter) {
            boolean isRanging = isERanging();
            if (!isRanging) {
                boolean isTrending = isERTrending();
                if (isTrending) {
                    rejectTrending.incrementAndGet();
                } else {
                    rejectNotRanging.incrementAndGet();
                }
                return;
            }
        }

        // 时段过滤
        if (ver.useHourFilter) {
            long epochSecond = currentTs / 1_000_000L;
            int hour = java.time.Instant.ofEpochSecond(epochSecond)
                    .atZone(ZoneId.systemDefault()).getHour();
            if (!ALLOWED_HOURS.contains(hour)) {
                rejectHourFilter.incrementAndGet();
                return;
            }
        }

        // 检查极值gap
        long gapMax = currentTs - ext.maxTs;
        long gapMin = currentTs - ext.minTs;
        boolean isHighExtreme = gapMax >= 0 && gapMax < BOUNCE_WINDOW_US;
        boolean isLowExtreme = gapMin >= 0 && gapMin < BOUNCE_WINDOW_US;

        if (!isHighExtreme && !isLowExtreme) return;

        // 去重
        if (isHighExtreme && ext.maxTs == ws.lastShortTs) return;
        if (isLowExtreme && ext.minTs == ws.lastLongTs) return;

        if (isHighExtreme) ws.lastShortTs = ext.maxTs;
        if (isLowExtreme) ws.lastLongTs = ext.minTs;

        // 加入回踩待确认队列
        BouncePending bp = new BouncePending();
        bp.extremeTs = currentTs;
        bp.extremePrice = currentPrice;
        bp.isHighExtreme = isHighExtreme;
        bp.maxData = ext.maxData;
        bp.minData = ext.minData;
        bp.ver = ver;
        bouncePending.put(currentTs, bp);
    }

    /**
     * 检查回踩待确认队列，处理回踩确认或突破取消
     */
    private void checkBouncePending(long currentTs, double currentPrice) {
        if (bouncePending.isEmpty()) return;

        List<Long> toRemove = new ArrayList<>();
        for (Map.Entry<Long, BouncePending> entry : bouncePending.entrySet()) {
            BouncePending bp = entry.getValue();
            long elapsed = currentTs - bp.extremeTs;

            if (elapsed > BOUNCE_WINDOW_US) {
                rejectNoBounce.incrementAndGet();
                toRemove.add(entry.getKey());
                continue;
            }

            double threshold = bp.ver.bounceThreshold;
            boolean bounced = false;
            boolean brokeOut = false;

            if (bp.isHighExtreme) {
                if (currentPrice <= bp.extremePrice * (1 - threshold)) {
                    bounced = true;
                } else if (currentPrice > bp.extremePrice * (1 + threshold)) {
                    brokeOut = true;
                }
            } else {
                if (currentPrice >= bp.extremePrice * (1 + threshold)) {
                    bounced = true;
                } else if (currentPrice < bp.extremePrice * (1 - threshold)) {
                    brokeOut = true;
                }
            }

            if (brokeOut) {
                rejectBreakout.incrementAndGet();
                toRemove.add(entry.getKey());
                continue;
            }

            if (bounced) {
                String direction = bp.isHighExtreme ? DIR_SHORT : DIR_LONG;
                createTrade(bp.maxData, bp.minData, direction, currentTs, currentPrice, bp.ver);
                toRemove.add(entry.getKey());
            }
        }

        for (Long key : toRemove) {
            bouncePending.remove(key);
        }
    }

    /**
     * 计算ER效率比率，判断是否为震荡市
     */
    private boolean isERanging() {
        if (erCount < ER_LOOKBACK) return false;
        if (erSum <= 0) return false;
        double netChange = getNetChange();
        double er = netChange / erSum;
        return er >= 0 && er < ER_RANGING;
    }

    private boolean isERTrending() {
        if (erCount < ER_LOOKBACK) return false;
        if (erSum <= 0) return false;
        double netChange = getNetChange();
        double er = netChange / erSum;
        return er > ER_TRENDING;
    }

    private double getNetChange() {
        if (prevClose <= 0 || dataBuffer.isEmpty()) return 0;
        long targetTs = dataBuffer.lastKey() - ER_LOOKBACK * 1_000_000L;
        Map.Entry<Long, KlineData> entry = dataBuffer.floorEntry(targetTs);
        if (entry == null) return 0;
        return Math.abs(prevClose - entry.getValue().close);
    }

    /**
     * 判断是否为上涨趋势
     * 计算当前价格与5分钟前价格的变化率，超过阈值则认为上涨
     */
    private boolean isTrendUp(double currentPrice, long currentTs, double threshold) {
        long trendStart = currentTs - FIVE_MINUTES_US;
        Map.Entry<Long, KlineData> entry = dataBuffer.floorEntry(trendStart);
        if (entry == null) return false;
        double priceOld = entry.getValue().close;
        return (currentPrice - priceOld) / priceOld > threshold;
    }

    /**
     * 判断是否为下跌趋势
     * 计算当前价格与5分钟前价格的变化率，超过阈值则认为下跌
     */
    private boolean isTrendDown(double currentPrice, long currentTs, double threshold) {
        long trendStart = currentTs - FIVE_MINUTES_US;
        Map.Entry<Long, KlineData> entry = dataBuffer.floorEntry(trendStart);
        if (entry == null) return false;
        double priceOld = entry.getValue().close;
        return (priceOld - currentPrice) / priceOld > threshold;
    }

    /**
     * 创建开单记录并存入数据库，同时加入待结算队列
     *
     * @param maxData   窗口内最高价K线
     * @param minData   窗口内最低价K线
     * @param direction 方向（多单/空单）
     * @param openTs    开单时间戳（微秒）
     * @param openPrice 开单价格
     * @param version   策略版本
     * @param settleUs  结算时间（微秒，从开单时间算起）
     */
    private boolean createTrade(KlineData maxData, KlineData minData,
                                String direction, long openTs, double openPrice, StrategyVersion ver) {
        try {
            String openTimeStr = formatTimestamp(openTs);
            String maxTimeStr = formatTimestamp(maxData.timestamp);
            String minTimeStr = formatTimestamp(minData.timestamp);

            EthTradeRecord record = EthTradeRecord.builder()
                    .openTimestamp(openTs)
                    .direction(direction)
                    .strategyVersion(ver.name)
                    .openPrice(String.format("%.8f", openPrice))
                    .high20min(String.format("%.8f", maxData.high))
                    .high20minTime(maxTimeStr)
                    .low20min(String.format("%.8f", minData.low))
                    .low20minTime(minTimeStr)
                    .build();

            tradeRecordService.createTrade(record);

            PendingTrade pt = new PendingTrade();
            pt.dbId = record.getId();
            pt.openTs = openTs;
            pt.targetTs = openTs + ver.settleUs;
            pt.direction = direction;
            pt.openPrice = String.format("%.8f", openPrice);
            pt.strategyVersion = ver.name;
            pendingTrades.put(pt.dbId, pt);

            ver.totalTrades.incrementAndGet();
            if (DIR_SHORT.equals(direction)) ver.shortTrades.incrementAndGet();
            else ver.longTrades.incrementAndGet();

            log("INFO", "交易", String.format("[%s] %s开仓 id=%d 价格=%.2f 最高=%.2f(%s) 最低=%.2f(%s) 结算时间=%s",
                    ver.name, direction, record.getId(), openPrice,
                    maxData.high, maxTimeStr, minData.low, minTimeStr,
                    formatTimestamp(pt.targetTs)));

            return true;
        } catch (Throwable e) {
            log("ERROR", "交易", "创建开单记录失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            System.err.println("❌ 创建开单记录失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查待结算交易，到期后自动结算
     * 胜负判断：多单→到期价>开单价为赢；空单→到期价<开单价为赢
     * 使用 floorEntry 容错查找，避免因缺秒导致超时
     */
    private void checkPendingSettlements() {
        if (pendingTrades.isEmpty()) return;

        long now = System.currentTimeMillis() * 1000;
        List<Long> toRemove = new ArrayList<>();

        for (Map.Entry<Long, PendingTrade> entry : pendingTrades.entrySet()) {
            PendingTrade pt = entry.getValue();

            if (now < pt.targetTs) continue;

            // 使用 floorEntry 容错查找（允许1秒误差）
            Map.Entry<Long, KlineData> floorEntry = dataBuffer.floorEntry(pt.targetTs);
            KlineData targetData = (floorEntry != null && pt.targetTs - floorEntry.getKey() <= ONE_MINUTE_US)
                    ? floorEntry.getValue() : null;

            if (targetData != null) {
                consecutiveMissCount.set(0);
                double openPrice = Double.parseDouble(pt.openPrice);
                double closePrice = targetData.close;
                boolean isWin = DIR_LONG.equals(pt.direction)
                        ? closePrice > openPrice
                        : closePrice < openPrice;
                StrategyVersion stv = verByName.get(pt.strategyVersion);
                double winR = (stv != null) ? stv.winReturn : WIN_PROFIT_USD;
                double loseR = (stv != null) ? stv.loseReturn : LOSE_PROFIT_USD;
                double profit = isWin ? winR : loseR;
                double profitPercent = (profit / openPrice) * 100;
                String status = isWin ? "盈利" : "亏损";

                tradeRecordService.settleTrade(pt.dbId, pt.targetTs,
                        String.format("%.8f", closePrice),
                        String.format("%.8f", profit),
                        String.format("%.2f", profitPercent),
                        status);

                StrategyVersion ver = verByName.get(pt.strategyVersion);
                if (ver != null) {
                    ver.settledCount.incrementAndGet();
                    if (isWin) ver.winCount.incrementAndGet();
                    else ver.loseCount.incrementAndGet();
                }

                log("INFO", "结算", String.format("[%s] %s结算 id=%d 开=%.2f 平=%.2f 方向%s 盈亏=%+.0fU(%.2f%%) %s",
                        pt.strategyVersion, pt.direction, pt.dbId,
                        openPrice, closePrice,
                        isWin ? "✓" : "✗",
                        profit, profitPercent, status));

                toRemove.add(entry.getKey());

            } else if (now - pt.targetTs > 5 * 60_000_000L) {
                int misses = consecutiveMissCount.incrementAndGet();
                double openPrice = Double.parseDouble(pt.openPrice);
                double profit = LOSE_PROFIT_USD;
                double profitPercent = (profit / openPrice) * 100;

                tradeRecordService.settleTrade(pt.dbId, pt.targetTs,
                        "0", String.format("%.8f", profit), String.format("%.2f", profitPercent), "超时");

                StrategyVersion ver = verByName.get(pt.strategyVersion);
                if (ver != null) ver.timeoutCount.incrementAndGet();

                log("WARN", "结算", String.format("[%s] %s超时结算 id=%d 目标时间=%s 无数据 盈亏=%+.0fU",
                        pt.strategyVersion, pt.direction, pt.dbId,
                        formatTimestamp(pt.targetTs), profit));

                // 连续缺失超过阈值，标记数据不健康
                if (misses >= 5 && dataHealthy) {
                    dataHealthy = false;
                    log("ERROR", "结算", "连续 " + misses + " 次结算数据缺失，标记数据不健康，暂停开单");
                    System.out.println("🚨 连续 " + misses + " 次结算数据缺失，数据可能已中断！");
                }

                toRemove.add(entry.getKey());
            }
        }

        for (Long id : toRemove) {
            pendingTrades.remove(id);
        }
    }

    /**
     * 清理超过4小时的旧数据（支持v6的2小时窗口+1小时结算）
     * 由定时任务每小时调用一次
     */
    private void cleanupOldData() {
        long cutoff = System.currentTimeMillis() * 1000 - FOUR_HOURS_US;
        int removed = 0;
        while (!dataBuffer.isEmpty() && dataBuffer.firstKey() < cutoff) {
            dataBuffer.pollFirstEntry();
            removed++;
        }
        if (removed > 0) {
            log("INFO", "清理", "清理旧数据: " + removed + " 条, 当前缓冲: " + dataBuffer.size() + " 条"
                    + " (cutoff=" + formatTimestamp(cutoff) + ")");
            System.out.println("🧹 清理旧数据: " + removed + " 条, 当前缓冲: " + dataBuffer.size() + " 条"
                    + " (cutoff=" + formatTimestamp(cutoff) + ")");
        }
        // 诊断：每5分钟打印一次缓冲时间范围（使用时间戳避免依赖调度精度）
        long now = System.currentTimeMillis();
        if (now - lastCleanupDiag > 300_000) {
            lastCleanupDiag = now;
            long first = dataBuffer.isEmpty() ? 0 : dataBuffer.firstKey();
            long last = dataBuffer.isEmpty() ? 0 : dataBuffer.lastKey();
            String range = formatTimestamp(first) + " ~ " + formatTimestamp(last)
                    + " (" + (first > 0 ? (last - first) / 60_000_000L : 0) + "分钟)";
            log("DEBUG", "清理", "缓冲范围: " + range);
            System.out.println("📊 缓冲范围: " + range);
        }
        // 同时清理过期的写入时间戳缓存
        long nowUs = System.currentTimeMillis() * 1000;
        if (nowUs - lastWrittenTsCleanup > WRITTEN_TS_TTL) {
            long tsCutoff = nowUs - WRITTEN_TS_TTL;
            writtenTimestamps.removeIf(ts -> ts < tsCutoff);
            lastWrittenTsCleanup = nowUs;
        }
    }

    /**
     * 定期打印运行状态
     * 由定时任务每5分钟调用一次
     */
    private void printStatus() {
        long runningMinutes = (System.currentTimeMillis() - startTime) / 60000;
        IEthTradeRecordService.TradeStats stats = tradeRecordService.getStats();

        long totalProfit = totalProfit();

        log("INFO", "状态", String.format("运行%dm 缓冲=%d 接收=%d 策略=%d 拒绝=%d 开单=%d 结算=%d 待结=%d 盈亏=%+dU",
                runningMinutes, dataBuffer.size(), klineReceived.get(),
                strategyCalls.get(), strategyRejected.get(),
                totalTrades(), settledCount(), stats.pendingTrades, totalProfit));

        System.out.println("\n┌──────────────────────────────────────────────────────────┐");
        System.out.println("│         实时交易状态 v11/v12/v13 (" + runningMinutes + "分钟)                    │");
        System.out.println("├──────────────────────────────────────────────────┤");
        System.out.println("│ 缓冲:" + fmt6(dataBuffer.size()) + "条 接收:" + fmt6(klineReceived.get()) + " 错误:" + fmt6(klineParseError.get()) + "│");
        System.out.println("│ 原始:" + fmt6(rawMessageCount.get()) + "   跳过:" + fmt6(skippedUnclosed.get()) + " 静默:" + fmt4((System.currentTimeMillis() - lastRawMessageAt) / 1000) + "s│");
        System.out.println("│ 健康:" + (dataHealthy ? "✅正常" : "❌异常") + " 重连:" + reconnectCount.get() + " 连续缺失:" + consecutiveMissCount.get() + "               │");
        System.out.println("│ 策略:" + fmt6(strategyCalls.get()) + "次 拒绝:" + fmt6(strategyRejected.get()) + "                    │");
        System.out.println("│ 拒绝: 空距" + rejectShortGap.get() + " 多距" + rejectLongGap.get() + " 去重" + rejectDedup.get() + " 冷却" + rejectCooling.get() + "│");
        System.out.println("│ ER过滤: 单边" + rejectTrending.get() + " 非震荡" + rejectNotRanging.get() + " 时段" + rejectHourFilter.get() + " 无回踩" + rejectNoBounce.get() + " 突破" + rejectBreakout.get() + "│");
        if (!lastDiag.isEmpty()) {
            System.out.println("│ 诊断: " + lastDiag.substring(0, Math.min(48, lastDiag.length())) + " │");
        }
        if (!lastDetailDiag.isEmpty()) {
            for (String part : lastDetailDiag.split("\\|")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    System.out.println("│ " + trimmed.substring(0, Math.min(48, trimmed.length())) + " │");
                }
            }
        }
        System.out.println("│ 全部: " + fmt6(totalTrades()) + "单 空:" + fmt6(shortTrades()) + " 多:" + fmt6(longTrades()) + " 待结:" + fmt6(stats.pendingTrades) + "             │");

        // 动态生成版本统计表（每行3个版本）
        for (int i = 0; i < versions.size(); i += 3) {
            StrategyVersion a = versions.get(i);
            StrategyVersion b = (i + 1 < versions.size()) ? versions.get(i + 1) : null;
            StrategyVersion c = (i + 2 < versions.size()) ? versions.get(i + 2) : null;

            System.out.println("├──────┬──────────────┬──────────────┬──────────────┤");
            System.out.println("│      │ " + pad14(a.label) + "│ " + pad14(b != null ? b.label : "") + "│ " + pad14(c != null ? c.label : "") + "│");
            System.out.println("├──────┼──────────────┼──────────────┼──────────────┤");
            System.out.println("│ 开单 │" + verShort(a) + "│" + (b != null ? verShort(b) : "              ") + "│" + (c != null ? verShort(c) : "              ") + "│");
            System.out.println("│ 结算 │" + verSettle(a) + "│" + (b != null ? verSettle(b) : "              ") + "│" + (c != null ? verSettle(c) : "              ") + "│");
            System.out.println("│ 盈亏 │" + verProfit(a) + "│" + (b != null ? verProfit(b) : "              ") + "│" + (c != null ? verProfit(c) : "              ") + "│");
            System.out.println("│ 过滤 │" + verFilter(a) + "│" + (b != null ? verFilter(b) : "              ") + "│" + (c != null ? verFilter(c) : "              ") + "│");
        }
        System.out.println("├──────┴──────────────┴──────────────┴──────────────┤");
        if (settledCount() > 0) {
            double winRate = winCount() * 100.0 / settledCount();
            System.out.println("│ 胜率:" + String.format("%.2f%%", winRate) + "  总盈亏:" + (totalProfit >= 0 ? "+" : "") + totalProfit + "U               │");
        }
        System.out.println("└──────────────────────────────────────────────────┘\n");
    }

    private String pad14(String s) { return String.format("%-14s", s != null ? s : ""); }
    private String fmt6(long n) { return String.format("%6d", n); }
    private String fmt4(long n) { return String.format("%4d", n); }
    private String fmt3(int n) { return String.format("%3d", n); }

    private String verShort(StrategyVersion v) {
        return fmt3(v.totalTrades.get()) + "空" + fmt3(v.shortTrades.get()) + "多" + fmt3(v.longTrades.get());
    }
    private String verSettle(StrategyVersion v) {
        return fmt3(v.settledCount.get()) + "盈" + fmt3(v.winCount.get()) + "亏" + fmt3(v.loseCount.get());
    }
    private String verProfit(StrategyVersion v) {
        return fmt3((int) v.calcProfit()) + "U         ";
    }
    private String verFilter(StrategyVersion v) {
        return fmt3(v.skippedByTrend.get()) + "超时" + fmt3(v.timeoutCount.get());
    }

    /**
     * 打印最终报告
     */
    private void printFinalReport() {
        long runningMinutes = (System.currentTimeMillis() - startTime) / 60000;
        long totalPnl = totalProfit();
        double winRate = settledCount() > 0 ? winCount() * 100.0 / settledCount() : 0;

        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║              最终交易报告 (4U赢/5U亏)            ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║ 运行: " + String.format("%d小时%d分钟", runningMinutes / 60, runningMinutes % 60));
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║ 全部: 开单" + totalTrades() + " 结算" + settledCount() + " 盈" + winCount() + " 亏" + loseCount() + " 超时" + timeoutCount());
        System.out.println("║ 总盈亏: " + (totalPnl >= 0 ? "+" : "") + totalPnl + "U  胜率: " + String.format("%.1f%%", winRate));
        System.out.println("╠══════════════════════════════════════════════════╣");

        for (StrategyVersion v : versions) {
            long p = v.calcProfit();
            System.out.println("║ " + pad14(v.label) + "开单:" + fmt3(v.totalTrades.get()) + " 盈:" + fmt3(v.winCount.get()) + " 亏:" + fmt3(v.loseCount.get()) + " 超时:" + fmt3(v.timeoutCount.get()) + " 盈亏:" + (p >= 0 ? "+" : "") + p + "U║");
        }

        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║ 过滤: v1跳过" + fmt3(verByName.get("v1").skippedByTrend.get()) + "  v2跳过" + fmt3(verByName.get("v2").skippedByTrend.get()));
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println("\n所有交易记录已保存到数据库表 eth_trade_record");

        String reportDetail = String.format("最终报告: 总盈亏=%+dU", totalPnl);
        for (StrategyVersion v : versions) {
            reportDetail += String.format(" %s=%+dU", v.name, v.calcProfit());
        }
        reportDetail += String.format(" 胜率=%.1f%% 开单=%d 结算=%d", winRate, totalTrades(), settledCount());
        log("INFO", "系统", reportDetail);
    }

    /**
     * 格式化时间戳显示
     * 自动兼容毫秒（13位）和微秒（16位）两种格式
     */
    private String formatTimestamp(long timestamp) {
        long ts = timestamp > 1000000000000000L ? timestamp / 1000 : timestamp;
        return SDF.format(new Date(ts));
    }

    /** 格式化数字（千分位分隔）*/
    private String formatNumber(long num) {
        return String.format("%,d", num);
    }

    // ==================== 下载数据 + 策略回放 ====================

    @Test
    public void testDownloadAndReplay() throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     下载数据 + 策略回放（含冷静期）           ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        RestTemplate restTemplate = new RestTemplate();

        List<EthTradeRecord> records = tradeRecordService.list(
                new LambdaQueryWrapper<EthTradeRecord>().eq(EthTradeRecord::getDeleted, 0));
        if (records == null || records.isEmpty()) {
            System.out.println("⚠️ 没有交易记录"); return;
        }
        System.out.println("📊 共 " + records.size() + " 条交易记录\n");

        long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
        for (EthTradeRecord r : records) {
            long openMs = r.getOpenTimestamp() / 1000;
            long start = openMs - 20 * 60 * 1000;
            long end = openMs + 25 * 60 * 1000;
            if (start < minTs) minTs = start;
            if (end > maxTs) maxTs = end;
        }

        System.out.println("📅 数据范围: " + formatTimestamp(minTs * 1000L) + " ~ " + formatTimestamp(maxTs * 1000L));
        long totalSeconds = (maxTs - minTs) / 1000;
        System.out.println("📏 总计约 " + formatNumber(totalSeconds) + " 秒 (" + String.format("%.1f", totalSeconds / 3600.0) + " 小时)\n");

        System.out.println("⬇️ 正在下载数据...");
        List<KlinePoint> allKlines = new ArrayList<>();
        long chunkStart = minTs;
        int batch = 0;
        int totalBatches = (int) ((maxTs - minTs) / (1000 * 1000) + 1);

        while (chunkStart < maxTs) {
            long chunkEnd = Math.min(chunkStart + 1000 * 1000, maxTs + 1000);
            String url = String.format(
                    "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=1000",
                    chunkStart, chunkEnd);
            try {
                Object[][] klines = restTemplate.getForObject(url, Object[][].class);
                if (klines != null) {
                    for (Object[] k : klines) {
                        allKlines.add(new KlinePoint(
                                Long.parseLong(k[0].toString()),
                                Double.parseDouble(k[1].toString()),
                                Double.parseDouble(k[2].toString()),
                                Double.parseDouble(k[3].toString()),
                                Double.parseDouble(k[4].toString())));
                    }
                }
            } catch (Exception e) {
                System.out.println("  ⚠️ 下载失败: " + e.getMessage());
            }

            batch++;
            if (batch % 10 == 0) {
                System.out.println("  进度: " + batch + "/" + totalBatches +
                        " (" + formatNumber(allKlines.size()) + " 条)");
            }
            Thread.sleep(150);
            chunkStart = chunkEnd;
        }

        System.out.println("✅ 下载完成，共 " + formatNumber(allKlines.size()) + " 条 K线\n");
        if (allKlines.isEmpty()) { System.out.println("⚠️ 无数据"); return; }

        allKlines.sort(Comparator.comparingLong(k -> k.ts));

        Map<Long, KlinePoint> klineMap = new HashMap<>();
        List<Long> timestamps = new ArrayList<>();
        for (KlinePoint kp : allKlines) {
            klineMap.put(kp.ts, kp);
            timestamps.add(kp.ts);
        }

        System.out.println("🔄 正在执行策略回放...\n");
        List<ReplayTrade> replayTrades = runStrategyOnKlines(allKlines, klineMap, timestamps);

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║              策略回放对比报告                 ║");
        System.out.println("╠══════════════════════════════════════════════╣");

        int origWin = 0, origLose = 0, replayWin = 0, replayLose = 0, replaySkip = 0;
        double origProfit = 0, replayProfit = 0;

        for (EthTradeRecord r : records) {
            double profit = r.getProfit() != null ? Double.parseDouble(r.getProfit()) : 0;
            if ("盈利".equals(r.getStatus())) origWin++; else origLose++;
            origProfit += profit;
        }

        for (ReplayTrade rt : replayTrades) {
            if (rt.passed) {
                if (rt.win) replayWin++; else replayLose++;
                replayProfit += rt.profit;
            } else {
                replaySkip++;
            }
        }

        System.out.println("║ 原始策略(DB):                                ║");
        System.out.println(String.format("║   交易数: %-4d 盈利: %-4d 亏损: %-4d    ║", records.size(), origWin, origLose));
        System.out.println(String.format("║   总收益: %+15.4f                        ║", origProfit));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ 新策略(下载数据+冷静期):                     ║");
        System.out.println(String.format("║   交易数: %-4d 盈利: %-4d 亏损: %-4d 取消: %-4d ║",
                replayTrades.size(), replayWin, replayLose, replaySkip));
        System.out.println(String.format("║   总收益: %+15.4f                        ║", replayProfit));
        System.out.println("╚══════════════════════════════════════════════╝");

        System.out.println("\n📋 逐笔对比:");
        int maxN = Math.max(records.size(), replayTrades.size());
        for (int i = 0; i < maxN; i++) {
            EthTradeRecord orig = i < records.size() ? records.get(i) : null;
            ReplayTrade replay = i < replayTrades.size() ? replayTrades.get(i) : null;

            String origStr = orig != null ? String.format("%s $%s %s(%.4f)",
                    orig.getDirection(), orig.getOpenPrice(), orig.getStatus(),
                    orig.getProfit() != null ? Double.parseDouble(orig.getProfit()) : 0) : "无";
            String replayStr = replay != null ? String.format("开单:%s $%.4f 结算:$%.4f %s(%.4f) %s",
                    formatTimestamp(replay.openTs * 1000L), replay.openPrice,
                    replay.settlePrice, replay.passed ? (replay.win ? "盈利" : "亏损") : "取消",
                    replay.profit, replay.passed ? (replay.coolingSecs + "s延迟") : "") : "无";

            System.out.println(String.format("  [%d] 原始: %s", i + 1, origStr));
            System.out.println(String.format("       新策略: %s", replayStr));
        }
    }

    private List<ReplayTrade> runStrategyOnKlines(List<KlinePoint> allKlines,
                                                  Map<Long, KlinePoint> klineMap,
                                                  List<Long> timestamps) {
        List<ReplayTrade> trades = new ArrayList<>();
        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        int left = 0;
        long lastTradeTs = -1;
        int skippedByCooling = 0;

        long TWENTY_MIN = 20 * 60 * 1000L;
        long ONE_MIN = 60 * 1000L;
        long THREE_MIN = 3 * 60 * 1000L;

        for (int right = 0; right < allKlines.size(); right++) {
            KlinePoint cur = allKlines.get(right);
            long curTs = cur.ts;

            while (!maxDeque.isEmpty() && allKlines.get(maxDeque.peekLast()).high <= cur.high)
                maxDeque.pollLast();
            maxDeque.offerLast(right);

            while (!minDeque.isEmpty() && allKlines.get(minDeque.peekLast()).low >= cur.low)
                minDeque.pollLast();
            minDeque.offerLast(right);

            long windowStart = curTs - TWENTY_MIN;
            while (timestamps.get(left) < windowStart) {
                if (maxDeque.peekFirst() == left) maxDeque.pollFirst();
                if (minDeque.peekFirst() == left) minDeque.pollFirst();
                left++;
            }

            if (right >= 1200 && right - left >= 60 && !maxDeque.isEmpty() && !minDeque.isEmpty()) {
                int maxIdx = maxDeque.peekFirst();
                int minIdx = minDeque.peekFirst();
                long maxTs = timestamps.get(maxIdx);
                long minTs = timestamps.get(minIdx);
                double maxHigh = allKlines.get(maxIdx).high;
                double minLow = allKlines.get(minIdx).low;

                if (curTs - maxTs >= ONE_MIN && curTs - maxTs <= THREE_MIN
                        && (lastTradeTs == -1 || curTs - lastTradeTs >= ONE_MIN)) {
                    ReplayTrade rt = applyCoolingAndOpen(allKlines, klineMap, right, true,
                            maxHigh, curTs, timestamps);
                    if (rt != null) { trades.add(rt); lastTradeTs = rt.openTs; }
                    else skippedByCooling++;
                }

                if (curTs - minTs >= ONE_MIN && curTs - minTs <= THREE_MIN
                        && (lastTradeTs == -1 || curTs - lastTradeTs >= ONE_MIN)) {
                    ReplayTrade rt = applyCoolingAndOpen(allKlines, klineMap, right, false,
                            minLow, curTs, timestamps);
                    if (rt != null) { trades.add(rt); lastTradeTs = rt.openTs; }
                    else skippedByCooling++;
                }
            }
        }

        System.out.println("  策略生成: " + trades.size() + " 笔, 冷静期取消: " + skippedByCooling);
        return trades;
    }

    private ReplayTrade applyCoolingAndOpen(List<KlinePoint> allKlines,
                                            Map<Long, KlinePoint> klineMap,
                                            int startIdx, boolean isShort,
                                            double extreme, long currentTs,
                                            List<Long> timestamps) {
        double currentExtreme = extreme;
        long lastBreakMs = currentTs - 1000;
        long maxTime = currentTs + 15 * 60 * 1000L;

        long openTimeMs = -1;
        double openAtPrice = 0;

        for (int i = startIdx; i < allKlines.size(); i++) {
            long t = allKlines.get(i).ts;
            if (t > maxTime) break;

            double high = allKlines.get(i).high;
            double low = allKlines.get(i).low;
            double close = allKlines.get(i).close;
            boolean broken = (isShort && high > currentExtreme) || (!isShort && low < currentExtreme);

            if (broken) {
                lastBreakMs = t;
                currentExtreme = isShort ? high : low;
            }

            if (t - lastBreakMs >= 61 * 1000L) {
                openTimeMs = t;
                openAtPrice = close;
                break;
            }
        }

        if (openTimeMs < 0) return null;

        ReplayTrade rt = new ReplayTrade();
        rt.openTs = openTimeMs;
        rt.openPrice = openAtPrice;
        rt.coolingSecs = (int) ((openTimeMs - currentTs) / 1000);
        rt.passed = true;

        long settleTs = openTimeMs + 10 * 60 * 1000L;
        KlinePoint settle = klineMap.get(settleTs);
        if (settle != null) {
            rt.settlePrice = settle.close;
            rt.profit = isShort ? (rt.openPrice - rt.settlePrice) : (rt.settlePrice - rt.openPrice);
            rt.win = rt.profit > 0;
        }

        return rt;
    }

    /**
     * 亏损交易原因分析 v2
     *
     * 分析维度：
     * 1. 亏损类型：趋势亏损（从未盈利）、反转亏损（曾盈利后反转）、震荡亏损（盈利过小）
     * 2. MFE（最大浮盈）/ MAE（最大浮亏）
     * 3. 空单 vs 多单亏损分布
     * 4. 亏损簇（连续亏损归为一组）
     * 5. 小时时段分布
     */
    @Test
    public void testAnalyzeLosingTrades() throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║           亏损交易原因分析 v2                 ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        RestTemplate restTemplate = new RestTemplate();
        List<EthTradeRecord> records = tradeRecordService.list(
                new LambdaQueryWrapper<EthTradeRecord>()
                        .eq(EthTradeRecord::getDeleted, 0)
                        .eq(EthTradeRecord::getStatus, "亏损"));

        if (records == null || records.isEmpty()) {
            System.out.println("⚠️ 没有亏损交易"); return;
        }
        System.out.println("📊 共 " + records.size() + " 笔亏损交易\n");

        int trendLoss = 0, reversalLoss = 0, whipLoss = 0;
        double totalMfe = 0, totalMae = 0;
        int shortCount = 0, longCount = 0, shortLoss = 0, longLoss = 0;
        Map<Integer, Integer> hourLossCount = new LinkedHashMap<>();

        List<LossCluster> clusters = new ArrayList<>();
        LossCluster currentCluster = null;

        for (int i = 0; i < records.size(); i++) {
            EthTradeRecord r = records.get(i);
            boolean isShort = "空单".equals(r.getDirection());
            long openTsMs = r.getOpenTimestamp() / 1000;
            double openPrice = Double.parseDouble(r.getOpenPrice());
            double origProfit = r.getProfit() != null ? Double.parseDouble(r.getProfit()) : 0;

            if (isShort) shortLoss++; else longLoss++;

            int hour = Integer.parseInt(formatTimestamp(openTsMs * 1000L).substring(11, 13));
            hourLossCount.merge(hour, 1, Integer::sum);

            Thread.sleep(200);
            long endMs = openTsMs + 10 * 60 * 1000;
            String url = String.format(
                    "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=600",
                    openTsMs, endMs + 1000);
            Object[][] klines = restTemplate.getForObject(url, Object[][].class);
            if (klines == null || klines.length == 0) {
                System.out.println(String.format("[%d/%d] ⚠️ 无数据", i + 1, records.size()));
                continue;
            }

            double mfe = isShort ? Double.MAX_VALUE : Double.MIN_VALUE;
            double mae = isShort ? Double.MIN_VALUE : Double.MAX_VALUE;
            long mfeTime = 0, maeTime = 0;
            boolean wasPositive = false;
            double maxPositive = 0;

            for (int j = 0; j < klines.length; j++) {
                long t = Long.parseLong(klines[j][0].toString());
                double high = Double.parseDouble(klines[j][2].toString());
                double low = Double.parseDouble(klines[j][3].toString());
                double close = Double.parseDouble(klines[j][4].toString());

                double favorable = isShort ? low : high;
                if (isShort && favorable < mfe) { mfe = favorable; mfeTime = t; }
                if (!isShort && favorable > mfe) { mfe = favorable; mfeTime = t; }

                double adverse = isShort ? high : low;
                if (isShort && adverse > mae) { mae = adverse; maeTime = t; }
                if (!isShort && adverse < mae) { mae = adverse; maeTime = t; }

                double paperProfit = isShort ? (openPrice - close) : (close - openPrice);
                if (paperProfit > 0) wasPositive = true;
                if (paperProfit > maxPositive) maxPositive = paperProfit;
            }

            double mfeProfit = isShort ? (openPrice - mfe) : (mfe - openPrice);
            double maeLoss = isShort ? (openPrice - mae) : (mae - openPrice);
            totalMfe += mfeProfit;
            totalMae += maeLoss;

            String lossType;
            if (!wasPositive) {
                lossType = "趋势";
                trendLoss++;
            } else if (maxPositive < Math.abs(origProfit) * 0.3) {
                lossType = "震荡";
                whipLoss++;
            } else {
                lossType = "反转";
                reversalLoss++;
            }

            if (currentCluster == null || !currentCluster.direction.equals(r.getDirection())
                    || openTsMs - currentCluster.lastOpenMs > 5 * 60 * 1000) {
                currentCluster = new LossCluster(r.getDirection(), openTsMs);
                clusters.add(currentCluster);
            }
            currentCluster.add(openTsMs, origProfit, mfeProfit, lossType);

            System.out.println(String.format("[%d] %s %s $%.4f → 亏:%.4f MFE:%+.4f(%s) MAE:%+.4f(%s) %s",
                    i + 1, isShort ? "空" : "多", lossType,
                    openPrice, origProfit,
                    mfeProfit, formatSec(mfeTime - openTsMs),
                    maeLoss, formatSec(maeTime - openTsMs),
                    formatTimestamp(openTsMs * 1000L)));
        }

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║              亏损原因汇总 v2                 ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 趋势亏损(从未盈利): %-4d  %5.1f%%           ║", trendLoss, trendLoss*100.0/records.size()));
        System.out.println(String.format("║ 反转亏损(曾盈利后反转): %-4d  %5.1f%%        ║", reversalLoss, reversalLoss*100.0/records.size()));
        System.out.println(String.format("║ 震荡亏损(盈利过小): %-4d  %5.1f%%            ║", whipLoss, whipLoss*100.0/records.size()));
        System.out.println(String.format("║ 平均 MFE: %+.4f  平均 MAE: %+.4f       ║", totalMfe/records.size(), totalMae/records.size()));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 空单亏损: %d  多单亏损: %d                   ║", shortLoss, longLoss));

        List<LossCluster> bigClusters = clusters.stream()
                .filter(c -> c.count >= 2).sorted((a,b) -> Integer.compare(b.count, a.count))
                .limit(5).collect(java.util.stream.Collectors.toList());
        if (!bigClusters.isEmpty()) {
            System.out.println("╠══════════════════════════════════════════════╣");
            System.out.println("║           最大亏损簇(Top5)                   ║");
            for (LossCluster c : bigClusters) {
                System.out.println(String.format("║ %s×%d 累计亏:%.4f 最佳MFE:%+.4f  %s~%s ║",
                        c.direction, c.count, c.totalLoss, c.bestMfe,
                        formatTimestamp(c.firstOpenMs * 1000L).substring(5, 19),
                        formatTimestamp(c.lastOpenMs * 1000L).substring(11, 19)));
            }
        }

        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║           亏损时段分布                        ║");
        hourLossCount.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    int bar = (int)(e.getValue() * 20.0 / records.size());
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < bar; k++) sb.append("█");
                    System.out.println(String.format("║ %02d:00 %s %d笔 ║", e.getKey(), sb.toString(), e.getValue()));
                });

        System.out.println("╚══════════════════════════════════════════════╝");
    }

    /** 格式化秒数（如 3s / 1m30s）*/
    private String formatSec(long diffMs) {
        long sec = diffMs / 1000;
        return sec < 60 ? sec + "s" : (sec/60) + "m" + (sec%60) + "s";
    }

    /**
     * 批量结算待结算交易
     * 从币安API拉取到期的K线数据，判断盈亏并更新数据库
     * 跳过未到结算时间的交易，统计成功/跳过/失败数量
     */
    @Test
    public void testSettlePendingTrades() throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║           批量结算待结算交易                  ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        RestTemplate restTemplate = new RestTemplate();
        List<EthTradeRecord> pending = tradeRecordService.list(
                new LambdaQueryWrapper<EthTradeRecord>()
                        .eq(EthTradeRecord::getDeleted, 0)
                        .eq(EthTradeRecord::getStatus, "待结算"));

        if (pending == null || pending.isEmpty()) {
            System.out.println("✅ 没有待结算的交易");
            return;
        }
        System.out.println("📊 共 " + pending.size() + " 笔待结算\n");

        long nowUs = System.currentTimeMillis() * 1000L;
        int settled = 0, skipped = 0, failed = 0;

        for (int i = 0; i < pending.size(); i++) {
            EthTradeRecord r = pending.get(i);
            boolean isShort = "空单".equals(r.getDirection());
            long openTsUs = r.getOpenTimestamp();
            long settleTsUs = openTsUs + 10 * 60 * 1000000L;
            double openPrice = Double.parseDouble(r.getOpenPrice());

            if (settleTsUs > nowUs) {
                skipped++;
                System.out.println(String.format("[%d/%d] ⏳ %s 开单:%s → 结算时间未到(%s)，跳过",
                        i + 1, pending.size(), isShort ? "空" : "多",
                        formatTimestamp(openTsUs), formatTimestamp(settleTsUs)));
                continue;
            }

            Thread.sleep(200);
            long settleMs = settleTsUs / 1000;
            String url = String.format(
                    "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=1",
                    settleMs, settleMs + 1000);
            Object[][] klines = restTemplate.getForObject(url, Object[][].class);

            if (klines == null || klines.length == 0) {
                failed++;
                System.out.println(String.format("[%d/%d] ⚠️ %s 开单:%s → API无数据，结算失败",
                        i + 1, pending.size(), isShort ? "空" : "多",
                        formatTimestamp(openTsUs)));
                continue;
            }

            double settlePrice = Double.parseDouble(klines[0][4].toString());
            double profit = isShort ? (openPrice - settlePrice) : (settlePrice - openPrice);
            double profitPercent = (profit / openPrice) * 100;
            String status = profit > 0 ? "盈利" : "亏损";

            tradeRecordService.settleTrade(r.getId(), settleTsUs,
                    String.format("%.4f", settlePrice),
                    String.format("%.4f", profit),
                    String.format("%.2f", profitPercent), status);
            settled++;

            System.out.println(String.format("[%d/%d] %s %s 开单:$%.4f 结算:$%.4f 收益:%+.4f (%+.2f%%)",
                    i + 1, pending.size(), status, isShort ? "空" : "多",
                    openPrice, settlePrice, profit, profitPercent));
        }

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println(String.format("║ 已结算: %d  跳过(未到): %d  失败: %d       ║", settled, skipped, failed));
        System.out.println("╚══════════════════════════════════════════════╝");
    }

    // ==================== K线数据连续性 & 实时写入 ====================

    /**
     * 确保数据库K线数据连续无断档，缺失则补全。
     * 在启动WebSocket实时交易前调用，保证回测数据完整性。
     */
    private void ensureDataContinuity() {
        System.out.println("\n🔍 正在检查数据库K线数据连续性...");
        try {
            long nowUs = System.currentTimeMillis() * 1000L;

            List<EthKlineSecond> latest = ethKlineSecondService.selectRecent(1);
            long lastDbUs;

            if (latest.isEmpty()) {
                System.out.println("⚠️ 数据库为空，将下载最近30天数据");
                lastDbUs = nowUs - 30L * 24 * 60 * 60 * 1000000L;
                dbIsMicro = true;
            } else {
                long rawTs = latest.get(0).getTimestamp();
                dbIsMicro = rawTs > 1000000000000000L;
                lastDbUs = dbIsMicro ? rawTs : rawTs * 1000;
                System.out.println("📌 数据库最后记录: " + formatTimestamp(lastDbUs)
                        + " [" + (dbIsMicro ? "微秒" : "毫秒") + "]");
            }

            long gapUs = nowUs - lastDbUs;
            long gapSec = gapUs / 1000000L;
            System.out.println("📏 数据滞后: " + gapSec + "秒 (" + String.format("%.1f", gapSec / 3600.0) + "小时)");

            if (gapSec <= 5) {
                System.out.println("✅ 数据已是最新，无需同步");
                return;
            }

            System.out.println("⬇️ 正在补全缺失数据 (" + gapSec + "秒)...");
            RestTemplate restTemplate = new RestTemplate();
            long chunkStartMs = (lastDbUs / 1000) + 1000;
            long endMs = nowUs / 1000;
            int totalDownloaded = 0;

            while (chunkStartMs < endMs) {
                long chunkEndMs = Math.min(chunkStartMs + 1000000, endMs);
                String url = String.format(
                        "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=1000",
                        chunkStartMs, chunkEndMs);

                try {
                    Object[][] klines = restTemplate.getForObject(url, Object[][].class);
                    if (klines != null && klines.length > 0) {
                        List<EthKlineSecond> batch = new ArrayList<>();
                        for (Object[] k : klines) {
                            long tsMs = Long.parseLong(k[0].toString());
                            long tsUs = dbIsMicro ? tsMs * 1000 : tsMs;
                            if (tsUs <= lastDbUs) continue;
                            if (!writtenTimestamps.add(tsUs)) continue;

                            EthKlineSecond ek = new EthKlineSecond();
                            ek.setTimestamp(tsUs);
                            ek.setOpen(k[1].toString());
                            ek.setHigh(k[2].toString());
                            ek.setLow(k[3].toString());
                            ek.setClose(k[4].toString());
                            ek.setVolume(k[5].toString());
                            batch.add(ek);
                        }
                        if (!batch.isEmpty()) {
                            ethKlineSecondService.saveBatchKlineData(batch);
                            totalDownloaded += batch.size();
                        }
                    }
                } catch (Exception e) {
                    System.out.println("  ⚠️ 下载失败: " + e.getMessage());
                }

                chunkStartMs = chunkEndMs;
                if (chunkStartMs < endMs) Thread.sleep(200);
            }

            System.out.println("✅ 数据补全完成，新增 " + totalDownloaded + " 条");

            List<EthKlineSecond> after = ethKlineSecondService.selectRecent(1);
            if (!after.isEmpty()) {
                long latestTs = after.get(0).getTimestamp();
                long latestUs = dbIsMicro ? latestTs : latestTs * 1000;
                System.out.println("📌 同步后最新记录: " + formatTimestamp(latestUs)
                        + " (滞后" + ((nowUs - latestUs) / 1000000L) + "秒)");
            }

        } catch (Exception e) {
            System.err.println("❌ 数据连续性检查失败: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println();
    }

    /**
     * 将实时K线数据写入缓冲，供批量刷新到数据库。
     * 自动去重：同一时间戳只写入一次。
     */
    private void writeKlineToDatabase(KlineData data) {
        if (ethKlineSecondService == null) return;

        // 自适应格式：检测WebSocket时间戳是毫秒(13位)还是微秒(16位)，统一转为DB格式
        long storeTs = data.timestamp;
        boolean dataIsMicro = storeTs > 1000000000000000L;
        if (dbIsMicro && !dataIsMicro) {
            storeTs = storeTs * 1000;
        } else if (!dbIsMicro && dataIsMicro) {
            storeTs = storeTs / 1000;
        }

        if (!writtenTimestamps.add(storeTs)) return;

        EthKlineSecond ek = new EthKlineSecond();
        ek.setTimestamp(storeTs);
        ek.setOpen(String.format("%.8f", data.open));
        ek.setHigh(String.format("%.8f", data.high));
        ek.setLow(String.format("%.8f", data.low));
        ek.setClose(String.format("%.8f", data.close));
        ek.setVolume(String.format("%.8f", data.volume));
        klineWriteBuffer.add(ek);

        if (klineWriteBuffer.size() >= KLINE_WRITE_BATCH) {
            flushKlineBuffer();
        }
    }

    /**
     * 批量刷新K线缓冲到数据库。
     * 由定时任务(30秒)和缓冲满时触发。
     */
    private void flushKlineBuffer() {
        synchronized (klineFlushLock) {
            if (klineWriteBuffer.isEmpty()) return;

            List<EthKlineSecond> batch = new ArrayList<>(klineWriteBuffer);
            klineWriteBuffer.clear();

            try {
                ethKlineSecondService.saveBatchKlineData(batch);
                log("DEBUG", "存储", "K线数据已刷新: " + batch.size() + " 条");
                if (batch.size() > 10) {
                    System.out.println("💾 K线数据已刷新: " + batch.size() + " 条");
                }
            } catch (Exception e) {
                log("ERROR", "存储", "K线数据刷新失败: " + e.getMessage());
                System.err.println("❌ K线数据刷新失败: " + e.getMessage());
                klineWriteBuffer.addAll(batch);
            }
        }
    }

    /**
     * 亏损交易全维度分析 v3
     *
     * 新增分析维度（相比v2）：
     * 1. 趋势对齐：顺趋势/逆趋势/横盘
     * 2. 亏损速度：急跌（<2分钟）/ 缓跌（>=2分钟）
     * 3. 结算后恢复：结算后价格是否回到开单价以上
     * 4. 逆趋势亏损详情
     * 5. 小时时段亏损分布（含金额）
     */
    @Test
    public void testAnalyzeLosingTradesV3() throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║       亏损交易全维度分析 V3                   ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        RestTemplate restTemplate = new RestTemplate();
        List<EthTradeRecord> records = tradeRecordService.list(
                new LambdaQueryWrapper<EthTradeRecord>()
                        .eq(EthTradeRecord::getDeleted, 0)
                        .eq(EthTradeRecord::getStatus, "亏损"));

        if (records == null || records.isEmpty()) {
            System.out.println("⚠️ 没有亏损交易");
            return;
        }
        System.out.println("📊 共 " + records.size() + " 笔亏损交易\n");

        int trendLoss = 0, reversalLoss = 0, whipLoss = 0;
        int trendAligned = 0, trendAgainst = 0, trendNeutral = 0;
        int suddenLoss = 0, gradualLoss = 0;
        int recoveredAfter = 0, notRecovered = 0;
        double totalMfe = 0, totalMae = 0, totalLoss = 0;
        int shortLoss = 0, longLoss = 0;
        Map<Integer, int[]> hourStats = new LinkedHashMap<>();
        List<LossDetail> details = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            EthTradeRecord r = records.get(i);
            boolean isShort = "空单".equals(r.getDirection());
            long openTsMs = r.getOpenTimestamp() / 1000;
            double openPrice = Double.parseDouble(r.getOpenPrice());
            double loss = Math.abs(r.getProfit() != null ? Double.parseDouble(r.getProfit()) : 0);
            totalLoss += loss;
            if (isShort) shortLoss++; else longLoss++;

            int hour = Integer.parseInt(formatTimestamp(openTsMs * 1000L).substring(11, 13));
            hourStats.computeIfAbsent(hour, k -> new int[2])[0]++;
            hourStats.computeIfAbsent(hour, k -> new int[2])[1] += (int)(loss * 10000);

            Thread.sleep(200);
            long endMs = openTsMs + 10 * 60 * 1000;
            String url = String.format(
                    "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=600",
                    openTsMs, endMs + 1000);
            Object[][] klines = restTemplate.getForObject(url, Object[][].class);
            if (klines == null || klines.length == 0) {
                System.out.println(String.format("[%d/%d] ⚠️ 无数据", i + 1, records.size()));
                continue;
            }

            double mfe = isShort ? Double.MAX_VALUE : Double.MIN_VALUE;
            double mae = isShort ? Double.MIN_VALUE : Double.MAX_VALUE;
            long mfeTime = 0, maeTime = 0;
            boolean wasPositive = false;
            double maxPositive = 0;
            long lossStartTime = 0;

            for (int j = 0; j < klines.length; j++) {
                long t = Long.parseLong(klines[j][0].toString());
                double high = Double.parseDouble(klines[j][2].toString());
                double low = Double.parseDouble(klines[j][3].toString());
                double close = Double.parseDouble(klines[j][4].toString());

                double favorable = isShort ? low : high;
                if (isShort && favorable < mfe) { mfe = favorable; mfeTime = t; }
                if (!isShort && favorable > mfe) { mfe = favorable; mfeTime = t; }

                double adverse = isShort ? high : low;
                if (isShort && adverse > mae) { mae = adverse; maeTime = t; }
                if (!isShort && adverse < mae) { mae = adverse; maeTime = t; }

                double paperProfit = isShort ? (openPrice - close) : (close - openPrice);
                if (paperProfit > 0) {
                    wasPositive = true;
                    if (paperProfit > maxPositive) maxPositive = paperProfit;
                }
                if (paperProfit < 0 && lossStartTime == 0) lossStartTime = t;
            }

            double mfeProfit = isShort ? (openPrice - mfe) : (mfe - openPrice);
            double maeLoss = isShort ? (mae - openPrice) : (openPrice - mae);
            if (isShort && mfe == Double.MAX_VALUE) mfeProfit = 0;
            if (!isShort && mfe == Double.MIN_VALUE) mfeProfit = 0;
            totalMfe += mfeProfit;
            totalMae += maeLoss;

            String lossType;
            if (!wasPositive) {
                lossType = "趋势亏损(从未盈利)";
                trendLoss++;
            } else if (maxPositive < 0.5) {
                lossType = "震荡亏损(盈利<0.5)";
                whipLoss++;
            } else {
                lossType = "反转亏损(曾盈利" + String.format("%.2f", maxPositive) + ")";
                reversalLoss++;
            }

            long trendStartMs = openTsMs - 5 * 60 * 1000;
            String trendUrl = String.format(
                    "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=300",
                    trendStartMs, openTsMs);
            Object[][] trendKlines = restTemplate.getForObject(trendUrl, Object[][].class);
            double trendChange = 0;
            if (trendKlines != null && trendKlines.length > 0) {
                double oldPrice = Double.parseDouble(trendKlines[0][4].toString());
                trendChange = (openPrice - oldPrice) / oldPrice;
            }

            String trendLabel;
            if (trendChange > 0.003) {
                trendLabel = "上涨";
                if (isShort) trendAgainst++; else trendAligned++;
            } else if (trendChange < -0.003) {
                trendLabel = "下跌";
                if (isShort) trendAligned++; else trendAgainst++;
            } else {
                trendLabel = "横盘";
                trendNeutral++;
            }

            long lossTime = lossStartTime > 0 ? (lossStartTime - openTsMs) / 1000 : 600;
            if (lossTime < 120) suddenLoss++; else gradualLoss++;

            long afterMs = openTsMs + 11 * 60 * 1000;
            String afterUrl = String.format(
                    "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1s&startTime=%d&endTime=%d&limit=600",
                    endMs, afterMs + 1000);
            Object[][] afterKlines = restTemplate.getForObject(afterUrl, Object[][].class);
            boolean recovered = false;
            if (afterKlines != null && afterKlines.length > 0) {
                for (Object[] ak : afterKlines) {
                    double price = Double.parseDouble(ak[4].toString());
                    double afterProfit = isShort ? (openPrice - price) : (price - openPrice);
                    if (afterProfit > 0) {
                        recovered = true;
                        break;
                    }
                }
            }
            if (recovered) recoveredAfter++; else notRecovered++;

            LossDetail d = new LossDetail();
            d.time = formatTimestamp(openTsMs * 1000L);
            d.dir = isShort ? "空" : "多";
            d.openPrice = openPrice;
            d.loss = loss;
            d.lossType = lossType;
            d.mfe = mfeProfit;
            d.mae = maeLoss;
            d.trendLabel = trendLabel;
            d.trendChange = trendChange;
            d.lossTime = lossTime;
            d.recovered = recovered;
            details.add(d);

            System.out.println(String.format("[%d] %s %s | 亏:%.4f | MFE:%+.4f MAE:%+.4f | 趋势:%+5.2f%%(%s) | 亏损起始:%ds | 恢复:%s | %s",
                    i + 1, d.time, d.dir, d.loss, d.mfe, d.mae,
                    trendChange * 100, trendLabel, lossTime, recovered ? "✅" : "❌", lossType));
        }

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║          全维度亏损分析汇总                   ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 总亏损笔数: %d  总亏损额: %.4f            ║", records.size(), totalLoss));
        System.out.println(String.format("║ 空单亏损: %d (%.1f%%)  多单亏损: %d (%.1f%%) ║",
                shortLoss, shortLoss * 100.0 / records.size(),
                longLoss, longLoss * 100.0 / records.size()));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ [亏损类型]                                   ║");
        System.out.println(String.format("║ 趋势亏损(从未盈利): %-4d  %5.1f%%           ║", trendLoss, trendLoss * 100.0 / records.size()));
        System.out.println(String.format("║ 反转亏损(曾盈利反转): %-4d  %5.1f%%          ║", reversalLoss, reversalLoss * 100.0 / records.size()));
        System.out.println(String.format("║ 震荡亏损(盈利过小): %-4d  %5.1f%%            ║", whipLoss, whipLoss * 100.0 / records.size()));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ [趋势对齐]                                   ║");
        System.out.println(String.format("║ 顺趋势: %-4d  逆趋势: %-4d  横盘: %-4d   ║", trendAligned, trendAgainst, trendNeutral));
        System.out.println(String.format("║ 逆趋势占比: %.1f%%                          ║",
                (trendAligned + trendAgainst + trendNeutral) > 0
                        ? trendAgainst * 100.0 / (trendAligned + trendAgainst + trendNeutral) : 0));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ [亏损速度]                                   ║");
        System.out.println(String.format("║ 急跌(<2分钟): %-4d  缓跌(>=2分钟): %-4d  ║", suddenLoss, gradualLoss));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ [结算后恢复]                                 ║");
        System.out.println(String.format("║ 恢复: %-4d  (%.1f%%)  未恢复: %-4d (%.1f%%) ║",
                recoveredAfter, recoveredAfter * 100.0 / records.size(),
                notRecovered, notRecovered * 100.0 / records.size()));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 平均MFE: %+.4f  平均MAE: %+.4f           ║", totalMfe / records.size(), totalMae / records.size()));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ [小时分布] 时段  笔数     累计亏损            ║");
        hourStats.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            int[] v = e.getValue();
            System.out.println(String.format("║ %02d:00     %-4d    %+8.4f              ║", e.getKey(), v[0], v[1] / 10000.0));
        });
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║ [逆趋势亏损详情]                             ║");
        details.stream().filter(d -> d.trendChange > 0.003 && "空".equals(d.dir)
                        || d.trendChange < -0.003 && "多".equals(d.dir))
                .forEach(d -> System.out.println(String.format(
                        "║ %s %s 亏%.4f 趋势%+.2f%% ║", d.time, d.dir, d.loss, d.trendChange * 100)));
        System.out.println("╚══════════════════════════════════════════════╝");
    }

    // ==================== 趋势过滤回测对比 ====================

    /**
     * 趋势过滤回测对比（大数据量版，300万+条）
     * 用6月26日0点(北京时间)后的K线数据，对比有/无趋势过滤的策略表现
     *
     * 性能优化：
     * - 原生数组替代HashMap/List，避免装箱开销
     * - 二分查找替代逐秒遍历，趋势查找 O(log n)
     * - 进度显示每20%打印一次
     *
     * 对比维度：
     * 1. 无趋势过滤
     * 2. 0.3%/5min 过滤（当前实盘参数）
     * 3. 0.4%/5min 过滤（稍严格）
     */
    @Test
    public void testBacktestTrendFilter() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     趋势过滤回测对比 - 6月26日0点至今        ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        long t0 = System.currentTimeMillis();

        // 从数据库最早K线开始（约2026-05-27），覆盖全部300万+条
        long startTs = 1779000000000000L;
        long endTs = System.currentTimeMillis() * 1000L;

        System.out.println("[1/3] 加载K线数据 (全量 300万+)...");
        System.out.println("  时间范围: " + formatTimestamp(startTs) + " ~ " + formatTimestamp(endTs));
        long t1 = System.currentTimeMillis();

        List<EthKlineSecond> allData = ethKlineSecondService.selectByTimeRange(startTs, endTs);
        if (allData == null || allData.isEmpty()) {
            System.out.println("❌ 无数据");
            return;
        }
        System.out.println("  加载完成: " + formatNumber(allData.size()) + " 条 (耗时" + (System.currentTimeMillis() - t1) + "ms)");

        // 排序
        System.out.println("  排序中...");
        allData.sort(Comparator.comparingLong(EthKlineSecond::getTimestamp));

        // 构建原生数组索引（避免HashMap装箱开销，300万+数据性能关键）
        System.out.println("  构建索引...");
        int n = allData.size();
        long[] tsArr = new long[n];
        double[] closeArr = new double[n];
        for (int i = 0; i < n; i++) {
            EthKlineSecond d = allData.get(i);
            tsArr[i] = d.getTimestamp();
            closeArr[i] = parseDouble(d.getClose());
        }
        System.out.println("  数据就绪: " + formatNumber(n) + " 条 (总耗时" + (System.currentTimeMillis() - t1) + "ms)\n");

        // 三种策略对比
        System.out.println("[2/3] 执行回测...\n");
        BacktestResult noFilter = runBacktestLarge(allData, tsArr, closeArr, 0, 0);
        BacktestResult filterV1 = runBacktestLarge(allData, tsArr, closeArr, 0.003, 5 * 60_000_000L);
        BacktestResult filterV2 = runBacktestLarge(allData, tsArr, closeArr, 0.004, 5 * 60_000_000L);

        // 打印对比报告
        System.out.println("\n[3/3] 回测报告");
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              回测对比报告 (全量, " + formatNumber(n) + "条)      ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║ 策略          笔数  盈利  亏损  胜率      总收益    被过滤  ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        printResult("无趋势过滤    ", noFilter.total, noFilter.win, noFilter.lose, noFilter.totalProfit, 0);
        printResult("0.3%/5min过滤 ", filterV1.total, filterV1.win, filterV1.lose, filterV1.totalProfit, filterV1.skippedByTrend);
        printResult("0.4%/5min过滤 ", filterV2.total, filterV2.win, filterV2.lose, filterV2.totalProfit, filterV2.skippedByTrend);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        System.out.println("\n📊 趋势过滤效果分析:");
        System.out.println("  无过滤 → " + noFilter.total + "笔 | 胜率" + String.format("%.1f%%", noFilter.winRate()) + " | 收益" + String.format("%.4f", noFilter.totalProfit));
        System.out.println("  0.3%/5min → " + filterV1.total + "笔 | 胜率" + String.format("%.1f%%", filterV1.winRate()) + " | 收益" + String.format("%.4f", filterV1.totalProfit) + " | 过滤" + filterV1.skippedByTrend + "笔");
        System.out.println("  0.4%/5min → " + filterV2.total + "笔 | 胜率" + String.format("%.1f%%", filterV2.winRate()) + " | 收益" + String.format("%.4f", filterV2.totalProfit) + " | 过滤" + filterV2.skippedByTrend + "笔");

        int sig1 = filterV1.total + filterV1.skippedByTrend;
        int sig2 = filterV2.total + filterV2.skippedByTrend;
        System.out.println("\n📌 结论:");
        System.out.println("  0.3%/5min: 总信号=" + sig1 + " | 过滤率=" + String.format("%.1f%%", sig1 > 0 ? 100.0 * filterV1.skippedByTrend / sig1 : 0));
        System.out.println("  0.4%/5min: 总信号=" + sig2 + " | 过滤率=" + String.format("%.1f%%", sig2 > 0 ? 100.0 * filterV2.skippedByTrend / sig2 : 0));

        if (filterV1.totalProfit > noFilter.totalProfit)
            System.out.println("  ✅ 0.3%/5min 收益提升: +" + String.format("%.4f", filterV1.totalProfit - noFilter.totalProfit));
        else
            System.out.println("  ⚠️ 0.3%/5min 收益变化: " + String.format("%.4f", filterV1.totalProfit - noFilter.totalProfit));
        if (filterV2.totalProfit > noFilter.totalProfit)
            System.out.println("  ✅ 0.4%/5min 收益提升: +" + String.format("%.4f", filterV2.totalProfit - noFilter.totalProfit));
        else
            System.out.println("  ⚠️ 0.4%/5min 收益变化: " + String.format("%.4f", filterV2.totalProfit - noFilter.totalProfit));

        System.out.println("\n📊 多空分开统计 (0.3%/5min):");
        System.out.println("  多单: " + filterV1.longTotal + "笔 | 盈" + filterV1.longWin + " 亏" + filterV1.longLose + " | 胜率" + String.format("%.1f%%", filterV1.longWinRate()) + " | 收益" + String.format("%.4f", filterV1.longProfit));
        System.out.println("  空单: " + filterV1.shortTotal + "笔 | 盈" + filterV1.shortWin + " 亏" + filterV1.shortLose + " | 胜率" + String.format("%.1f%%", filterV1.shortWinRate()) + " | 收益" + String.format("%.4f", filterV1.shortProfit));

        System.out.println("\n⏱️ 总耗时: " + (System.currentTimeMillis() - t0) / 1000 + "s");
    }

    /**
     * 执行回测（大数据量优化版）
     * 原生数组 + 二分查找，避免反复解析String和HashMap装箱，适配300万+数据
     */
    private BacktestResult runBacktestLarge(List<EthKlineSecond> allData,
                                            long[] tsArr, double[] closeArr,
                                            double trendThreshold, long trendLookbackUs) {
        String label = trendThreshold > 0
                ? String.format("%.1f%%/%.0fmin", trendThreshold * 100, trendLookbackUs / 60_000_000.0)
                : "无过滤";
        System.out.println("  回测: " + label + " ...");
        long t0 = System.currentTimeMillis();

        BacktestResult result = new BacktestResult();
        boolean enableTrend = trendThreshold > 0 && trendLookbackUs > 0;
        int n = allData.size();

        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        int left = 0;
        long lastTradeTs = -1, lastShortTs = -1, lastLongTs = -1;
        int lastProgress = 0;

        for (int right = 0; right < n; right++) {
            long curTs = tsArr[right];
            double curPrice = closeArr[right];

            // 进度显示（每20%）
            int progress = right * 100 / n;
            if (progress >= lastProgress + 20) {
                System.out.println("    进度: " + progress + "% | 已开单: " + result.total + " | 过滤: " + result.skippedByTrend);
                lastProgress = progress;
            }

            // 维护单调递减队列（窗口内最大值）
            while (!maxDeque.isEmpty() && closeArr[maxDeque.peekLast()] <= curPrice)
                maxDeque.pollLast();
            maxDeque.offerLast(right);

            // 维护单调递增队列（窗口内最小值）
            while (!minDeque.isEmpty() && closeArr[minDeque.peekLast()] >= curPrice)
                minDeque.pollLast();
            minDeque.offerLast(right);

            // 滑动窗口：移除20分钟前的数据
            long windowStart = curTs - TWENTY_MINUTES_US;
            while (left < right && tsArr[left] < windowStart) {
                if (maxDeque.peekFirst() != null && maxDeque.peekFirst() == left) maxDeque.pollFirst();
                if (minDeque.peekFirst() != null && minDeque.peekFirst() == left) minDeque.pollFirst();
                left++;
            }

            if (maxDeque.isEmpty() || minDeque.isEmpty()) continue;

            int maxIdx = maxDeque.peekFirst();
            int minIdx = minDeque.peekFirst();
            long maxTs = tsArr[maxIdx];
            long minTs = tsArr[minIdx];

            boolean canTrade = (lastTradeTs == -1 || curTs - lastTradeTs >= ONE_MINUTE_US);

            // === 空单信号 ===
            if (curTs - maxTs >= ONE_MINUTE_US && curTs - maxTs <= THREE_MINUTES_US
                    && maxTs != lastShortTs && canTrade) {
                boolean skip = false;
                if (enableTrend && isTrendUpFast(tsArr, closeArr, right, curPrice, trendLookbackUs, trendThreshold)) {
                    skip = true;
                    result.skippedByTrend++;
                }
                if (!skip) {
                    int settleIdx = binarySearch(tsArr, curTs + TEN_MINUTES_US);
                    if (settleIdx >= 0 && settleIdx < n) {
                        result.addTrade("空单", curPrice - closeArr[settleIdx]);
                    }
                    lastShortTs = maxTs;
                    lastTradeTs = curTs;
                }
            }

            // === 多单信号 ===
            if (curTs - minTs >= ONE_MINUTE_US && curTs - minTs <= THREE_MINUTES_US
                    && minTs != lastLongTs && canTrade) {
                boolean skip = false;
                if (enableTrend && isTrendDownFast(tsArr, closeArr, right, curPrice, trendLookbackUs, trendThreshold)) {
                    skip = true;
                    result.skippedByTrend++;
                }
                if (!skip) {
                    int settleIdx = binarySearch(tsArr, curTs + TEN_MINUTES_US);
                    if (settleIdx >= 0 && settleIdx < n) {
                        result.addTrade("多单", closeArr[settleIdx] - curPrice);
                    }
                    lastLongTs = minTs;
                    lastTradeTs = curTs;
                }
            }
        }
        System.out.println("    完成: " + result.total + "笔 | 过滤" + result.skippedByTrend + "笔 | 耗时" + (System.currentTimeMillis() - t0) + "ms\n");
        return result;
    }

    /** 二分查找：找到 >= targetTs 的最小索引 */
    private int binarySearch(long[] tsArr, long targetTs) {
        int lo = 0, hi = tsArr.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (tsArr[mid] < targetTs) lo = mid + 1;
            else if (tsArr[mid] > targetTs) hi = mid - 1;
            else return mid;
        }
        return lo < tsArr.length ? lo : -1;
    }

    /** 二分查找：找到 <= targetTs 的最大索引（趋势价格查找）*/
    private int trendLookupIdx(long[] tsArr, long targetTs) {
        int lo = 0, hi = tsArr.length - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (tsArr[mid] <= targetTs) { best = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return best;
    }

    private boolean isTrendUpFast(long[] tsArr, double[] closeArr, int curIdx,
                                  double curPrice, long lookbackUs, double threshold) {
        int oldIdx = trendLookupIdx(tsArr, tsArr[curIdx] - lookbackUs);
        if (oldIdx < 0) return false;
        double oldPrice = closeArr[oldIdx];
        return (curPrice - oldPrice) / oldPrice > threshold;
    }

    private boolean isTrendDownFast(long[] tsArr, double[] closeArr, int curIdx,
                                    double curPrice, long lookbackUs, double threshold) {
        int oldIdx = trendLookupIdx(tsArr, tsArr[curIdx] - lookbackUs);
        if (oldIdx < 0) return false;
        double oldPrice = closeArr[oldIdx];
        return (oldPrice - curPrice) / oldPrice > threshold;
    }

    private void printResult(String label, int total, int win, int lose, double profit, int skipped) {
        double rate = (win + lose) > 0 ? win * 100.0 / (win + lose) : 0;
        System.out.println(String.format("║ %-12s %4d  %4d  %4d  %5.1f%%  %+10.4f  %6d  ║",
                label, total, win, lose, rate, profit, skipped));
    }

    private double parseDouble(String s) {
        if (s == null) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    static class BacktestResult {
        int total, win, lose, skippedByTrend;
        int shortTotal, shortWin, shortLose;
        int longTotal, longWin, longLose;
        double totalProfit, shortProfit, longProfit;

        void addTrade(String dir, double profit) {
            total++;
            totalProfit += profit;
            if (profit > 0) win++; else lose++;
            if ("空单".equals(dir)) {
                shortTotal++;
                shortProfit += profit;
                if (profit > 0) shortWin++; else shortLose++;
            } else {
                longTotal++;
                longProfit += profit;
                if (profit > 0) longWin++; else longLose++;
            }
        }

        double winRate() { return (win + lose) > 0 ? win * 100.0 / (win + lose) : 0; }
        double longWinRate() { return (longWin + longLose) > 0 ? longWin * 100.0 / (longWin + longLose) : 0; }
        double shortWinRate() { return (shortWin + shortLose) > 0 ? shortWin * 100.0 / (shortWin + shortLose) : 0; }
    }
}