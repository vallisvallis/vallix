package com.alphay.boot.web.test;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.mapper.EthKlineSecondMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 交易策略回测测试（高效大数据版）
 *
 * 针对百万级数据优化：
 * - 使用滑动窗口 + 单调队列 O(n) 复杂度
 * - SXSSF 流式写入Excel，避免OOM
 * - 支持微秒/毫秒两种时间戳格式
 *
 * 策略逻辑：
 * 1. 遍历每一秒作为当前时间
 * 2. 在过去20分钟窗口找极值（最高/最低价）
 * 3. 极值出现1~3分钟后开单（等待确认拐点）
 * 4. 5分钟趋势过滤：上涨趋势不做空，下跌趋势不做多
 * 5. 10分钟后到期结算，判断盈亏
 *
 * 功能：
 * - testTradingStrategy(): V1策略回测（参数化版本）
 * - testTradingStrategyWithCooling(): 含冷静期回测（等待1分钟确认不突破再开单）
 *
 * @author d3code
 */
@SpringBootTest
public class TradingStrategyBacktest {

    // ==================== 依赖注入 ====================

    @Autowired
    private EthKlineSecondMapper ethKlineSecondMapper;

    // ==================== 常量定义（微秒级）====================

    private static final long TWENTY_MINUTES_US = 1200000000L;    // 20分钟极值窗口
    private static final long TEN_MINUTES_US = 600000000L;       // 10分钟结算
    private static final long ONE_MINUTE_US = 60000000L;         // 1分钟
    private static final long THREE_MINUTES_US = 180000000L;     // 开单时间与极值时间的最大间隔
    private static final int BATCH_WRITE_SIZE = 10000;           // Excel批量刷写阈值

    // ==================== 内部数据模型 ====================

    /**
     * Vallis策略枚举，封装策略参数，便于扩展新策略
     * 与 RealTimeTradingTest 中的 StrategyVersion 保持一致
     */
    enum VallisStrategy {
        BASE("base(20m/10m)", 20, 10, 3, 0, false),
        V1("v1(20m/10m 0.2%趋势)", 20, 10, 3, 0.002, true),
        V2("v2(20m/10m 0.5%趋势)", 20, 10, 3, 0.005, true),
        V5("v5(20m/30m)", 20, 30, 3, 0, false),
        V3("v3(15m/10m)", 15, 10, 1, 0, false),
        V4("v4(30m/10m)", 30, 10, 1, 0, false),
        V6("v6(2h/1h)", 120, 60, 1, 0, false),
        // V11/V12/V13 对应 RealTimeTradingTest 中的三个实时版本
        V11("v11(base 无过滤)", 20, 10, 3, 0, false),      // 基础版，无趋势过滤
        V12("v12(0.3%趋势)", 20, 10, 3, 0.003, true),      // 0.3%趋势过滤
        V13("v13(0.4%趋势)", 20, 10, 3, 0.004, true);      // 0.4%趋势过滤

        /** 策略名称 */
        final String name;
        /** 极值窗口大小（分钟）*/
        final int windowMinutes;
        /** 结算时间（分钟）*/
        final int settleMinutes;
        /** 极值距当前允许的最小时差（分钟）*/
        final int gapMinutes;
        /** 趋势强度阈值（0=无过滤, 0.002=0.2%, 0.005=0.5%）*/
        final double trendThreshold;
        /** 是否启用趋势过滤 */
        final boolean useTrendFilter;

        VallisStrategy(String name, int windowMinutes, int settleMinutes,
                       int gapMinutes, double trendThreshold, boolean useTrendFilter) {
            this.name = name;
            this.windowMinutes = windowMinutes;
            this.settleMinutes = settleMinutes;
            this.gapMinutes = gapMinutes;
            this.trendThreshold = trendThreshold;
            this.useTrendFilter = useTrendFilter;
        }
    }

    /**
     * 交易记录 DTO（用于Excel导出）
     */
    public static class TradeRecord {
        private String openTime;
        private long openTimestamp;
        private String direction;
        private double openPrice;
        private double high20min;
        private String high20minTime;
        private double low20min;
        private String low20minTime;
        private String triggerExtreme;   // 触发开单的极值
        private String triggerExtremeTime; // 极值出现时间
        private String tenMinuteLaterTime;
        private double tenMinuteLaterPrice;
        private double profit;
        private double profitPercent;
        private boolean win;
        // 冷静期相关字段
        private long delayedOpenTimestamp;
        private double delayedOpenPrice;
        private int coolingSeconds;
        private boolean coolingPassed;
        private String coolingStatus;

        public String getOpenTime() { return openTime; }
        public void setOpenTime(String openTime) { this.openTime = openTime; }
        public long getOpenTimestamp() { return openTimestamp; }
        public void setOpenTimestamp(long openTimestamp) { this.openTimestamp = openTimestamp; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public double getOpenPrice() { return openPrice; }
        public void setOpenPrice(double openPrice) { this.openPrice = openPrice; }
        public double getHigh20min() { return high20min; }
        public void setHigh20min(double high20min) { this.high20min = high20min; }
        public String getHigh20minTime() { return high20minTime; }
        public void setHigh20minTime(String high20minTime) { this.high20minTime = high20minTime; }
        public double getLow20min() { return low20min; }
        public void setLow20min(double low20min) { this.low20min = low20min; }
        public String getLow20minTime() { return low20minTime; }
        public void setLow20minTime(String low20minTime) { this.low20minTime = low20minTime; }
        public String getTriggerExtreme() { return triggerExtreme; }
        public void setTriggerExtreme(String triggerExtreme) { this.triggerExtreme = triggerExtreme; }
        public String getTriggerExtremeTime() { return triggerExtremeTime; }
        public void setTriggerExtremeTime(String triggerExtremeTime) { this.triggerExtremeTime = triggerExtremeTime; }
        public String getTenMinuteLaterTime() { return tenMinuteLaterTime; }
        public void setTenMinuteLaterTime(String tenMinuteLaterTime) { this.tenMinuteLaterTime = tenMinuteLaterTime; }
        public double getTenMinuteLaterPrice() { return tenMinuteLaterPrice; }
        public void setTenMinuteLaterPrice(double tenMinuteLaterPrice) { this.tenMinuteLaterPrice = tenMinuteLaterPrice; }
        public double getProfit() { return profit; }
        public void setProfit(double profit) { this.profit = profit; }
        public double getProfitPercent() { return profitPercent; }
        public void setProfitPercent(double profitPercent) { this.profitPercent = profitPercent; }
        public boolean isWin() { return win; }
        public void setWin(boolean win) { this.win = win; }
        public long getDelayedOpenTimestamp() { return delayedOpenTimestamp; }
        public void setDelayedOpenTimestamp(long delayedOpenTimestamp) { this.delayedOpenTimestamp = delayedOpenTimestamp; }
        public double getDelayedOpenPrice() { return delayedOpenPrice; }
        public void setDelayedOpenPrice(double delayedOpenPrice) { this.delayedOpenPrice = delayedOpenPrice; }
        public int getCoolingSeconds() { return coolingSeconds; }
        public void setCoolingSeconds(int coolingSeconds) { this.coolingSeconds = coolingSeconds; }
        public boolean isCoolingPassed() { return coolingPassed; }
        public void setCoolingPassed(boolean coolingPassed) { this.coolingPassed = coolingPassed; }
        public String getCoolingStatus() { return coolingStatus; }
        public void setCoolingStatus(String coolingStatus) { this.coolingStatus = coolingStatus; }
    }

    // ==================== 测试方法 ====================

    /**
     * 回测 RealTimeTradingTest 中的 V11/V12/V13 三个策略版本
     * 数据时间范围：今天下午3点20分 至 当前时间
     * 
     * V11 = base(无过滤) - 始终开单
     * V12 = v1(0.3%)     - 0.3%趋势过滤
     * V13 = v2(0.4%)     - 0.4%趋势过滤
     */
    @Test
    public void testV11V12V13FromToday1520() throws IOException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           V11/V12/V13 策略回测（数据从今天15:20开始）            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        long globalStart = System.currentTimeMillis();

        // ========== 阶段1: 加载数据（从今天下午3点20分开始） ==========
        System.out.println("[1/3] 正在加载数据（今天15:20 至 当前）...");
        
        // 获取今天下午3点20分的时间戳
        LocalDate today = LocalDate.now();
        long startTimeMs = java.time.LocalDateTime.of(today.getYear(), today.getMonth(), today.getDayOfMonth(), 15, 20, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        boolean isMicro = !sample.isEmpty() && sample.get(0).getTimestamp() > 1000000000000000L;
        long startTs = isMicro ? startTimeMs * 1000 : startTimeMs;
        long endTs = isMicro ? System.currentTimeMillis() * 1000 : System.currentTimeMillis();
        
        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(startTs, endTs);

        if (allData.isEmpty()) {
            System.out.println("数据库中没有今天15:20之后的数据");
            return;
        }
        int N = allData.size();
        System.out.println("数据加载完成，共 " + formatNumber(N) + " 条");
        System.out.println("时间范围: " + formatTimestamp(startTs) + " ~ " + formatTimestamp(endTs));

        // ========== 阶段2: 排序 + 预计算原始数组 ==========
        System.out.println("\n[2/3] 排序并预计算原始数组...");
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));

        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];
        Map<Long, Double> settlePriceMap = new HashMap<>(N);

        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
            settlePriceMap.put(timestamps[i], closes[i]);
        }
        allData = null;
        System.gc();

        // ========== 阶段3: 执行三个版本回测 ==========
        System.out.println("\n[3/3] 执行 V11/V12/V13 回测...\n");
        
        VallisStrategy[] versions = {VallisStrategy.V11, VallisStrategy.V12, VallisStrategy.V13};
        List<VersionStats> statsList = new ArrayList<>();
        
        for (VallisStrategy strategy : versions) {
            long verStart = System.currentTimeMillis();
            List<TradeRecord> records = executeSlidingWindowStrategyFast(
                    timestamps, closes, highs, lows, settlePriceMap, N, strategy);
            long verEnd = System.currentTimeMillis();
            
            VersionStats stats = buildVersionStats(strategy.name, records, verEnd - verStart);
            statsList.add(stats);
            
            System.out.println("  ✅ " + strategy.name + " 完成: " + stats.totalTrades + "单 "
                    + String.format("盈亏%+.2fU", stats.fixedProfit) + " "
                    + String.format("胜率%.1f%%", stats.winRate) + " "
                    + "耗时" + formatTime(verEnd - verStart));
        }

        // ========== 输出报告 ==========
        String line60 = createRepeatedString('=', 60);
        System.out.println("\n" + line60);
        System.out.println("              V11/V12/V13 回测对比报告");
        System.out.println(line60);
        System.out.printf("%-20s %8s %8s %8s %8s %8s %10s\n",
                "策略", "开单", "盈利", "亏损", "胜率", "盈亏(U)", "耗时");
        for (VersionStats s : statsList) {
            System.out.printf("%-20s %8d %8d %8d %7.1f%% %+9.2f %10s\n",
                    s.name, s.totalTrades, s.winCount, s.loseCount,
                    s.winRate, s.fixedProfit, formatTime(s.elapsedMs));
        }

        long globalEnd = System.currentTimeMillis();
        String outputPath = "D:\\trading_backtest_v11v12v13.xlsx";
        saveAllVersionsToExcel(outputPath, versions, 
                Arrays.asList(executeSlidingWindowStrategyFast(timestamps, closes, highs, lows, settlePriceMap, N, VallisStrategy.V11),
                              executeSlidingWindowStrategyFast(timestamps, closes, highs, lows, settlePriceMap, N, VallisStrategy.V12),
                              executeSlidingWindowStrategyFast(timestamps, closes, highs, lows, settlePriceMap, N, VallisStrategy.V13)),
                statsList, globalEnd - globalStart);
        
        System.out.println("\n✅ 回测结果已保存到: " + outputPath);
        System.out.println("总耗时: " + formatTime(globalEnd - globalStart));
    }

    /**
     * 创建重复字符组成的字符串（兼容Java 8）
     */
    private String createRepeatedString(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 一键回测所有7个策略版本，输出对比报告（高速版）
     * 数据只加载一次，预计算原始数组，多版本并行执行
     * 针对300万+数据优化：parseDouble只做一次，primitive数组避免装箱
     */
    @Test
    public void testAllVersions() throws IOException, InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       ETH/USDT 全版本策略回测对比 (高速并行版)                  ║");
        System.out.println("║       base/v1/v2/v3/v4/v5/v6 共7个版本                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        long globalStart = System.currentTimeMillis();

        // ========== 阶段1: 加载数据 ==========
        System.out.println("[1/4] 正在加载数据（2026-06-1 至今）...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        boolean isMicro = !sample.isEmpty() && sample.get(0).getTimestamp() > 1000000000000000L;
        long startMs = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long startTs = isMicro ? startMs * 1000 : startMs;
        long endTs = isMicro ? System.currentTimeMillis() * 1000 : System.currentTimeMillis();
        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(startTs, endTs);

        if (allData.isEmpty()) {
            System.out.println("数据库中没有数据");
            return;
        }
        int N = allData.size();
        System.out.println("数据加载完成，共 " + formatNumber(N) + " 条");
        System.out.println("时间戳格式: " + (isMicro ? "微秒级" : "毫秒级"));

        // ========== 阶段2: 排序 + 预计算原始数组（只做一次parseDouble） ==========
        System.out.println("\n[2/4] 排序并预计算原始数组...");
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        validateTimeContinuity(allData);

        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];
        Map<Long, Double> settlePriceMap = new HashMap<>(N);

        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
            settlePriceMap.put(timestamps[i], closes[i]);
        }
        allData = null; // 释放内存
        System.gc();
        System.out.println("预计算完成，原始数组已就绪 (" + formatNumber(N) + " 条)");

        // ========== 阶段3: 并行执行7个版本 ==========
        System.out.println("\n[3/4] 并行执行各版本策略回测...\n");

        VallisStrategy[] versions = VallisStrategy.values();
        List<List<TradeRecord>> allResults = new ArrayList<>(versions.length);
        List<VersionStats> allStats = new ArrayList<>(versions.length);
        for (int i = 0; i < versions.length; i++) {
            allResults.add(null);
            allStats.add(null);
        }

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(versions.length, Runtime.getRuntime().availableProcessors()));

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(versions.length);
        for (int vi = 0; vi < versions.length; vi++) {
            final int idx = vi;
            final VallisStrategy strategy = versions[vi];
            executor.submit(() -> {
                try {
                    long verStart = System.currentTimeMillis();
                    List<TradeRecord> records = executeSlidingWindowStrategyFast(
                            timestamps, closes, highs, lows, settlePriceMap, N, strategy);
                    long verEnd = System.currentTimeMillis();

                    synchronized (allResults) {
                        allResults.set(idx, records);
                        allStats.set(idx, buildVersionStats(strategy.name, records, verEnd - verStart));
                    }

                    VersionStats s = allStats.get(idx);
                    System.out.println("  ✅ " + strategy.name + " 完成: " + s.totalTrades + "单 "
                            + String.format("盈亏%+.2fU", s.fixedProfit) + " "
                            + String.format("胜率%.1f%%", s.winRate) + " "
                            + "耗时" + formatTime(verEnd - verStart));
                } catch (Exception e) {
                    System.err.println("  ❌ " + strategy.name + " 异常: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        System.out.println();

        // ========== 阶段4: 输出报告 ==========
        System.out.println("[4/4] 生成对比报告...");
        printVersionComparison(allStats);

        long globalEnd = System.currentTimeMillis();
        String outputPath = "D:\\trading_backtest_all_versions.xlsx";
        saveAllVersionsToExcel(outputPath, versions, allResults, allStats, globalEnd - globalStart);
        System.out.println("\n✅ 全版本回测结果已保存到: " + outputPath);

        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     全版本策略回测完成，总耗时: " + formatTime(globalEnd - globalStart) + "              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    /**
     * 高速版滑动窗口策略（使用原始数组，避免装箱和重复parseDouble）
     * 
     * @param timestamps     时间戳数组（已排序）
     * @param closes         收盘价数组
     * @param highs          最高价数组
     * @param lows           最低价数组
     * @param settlePriceMap 时间戳→收盘价映射（结算用）
     * @param N              数据条数
     * @param strategy       策略参数
     */
    private List<TradeRecord> executeSlidingWindowStrategyFast(
            long[] timestamps, double[] closes, double[] highs, double[] lows,
            Map<Long, Double> settlePriceMap, int N, VallisStrategy strategy) {

        long windowUs = strategy.windowMinutes * 60L * 1000000L;
        long settleUs = strategy.settleMinutes * 60L * 1000000L;
        long gapUs = strategy.gapMinutes * 60L * 1000000L;
        long trendLookbackUs = 5 * 60L * 1000000L;

        List<TradeRecord> tradeRecords = new ArrayList<>();
        int[] maxDeque = new int[N];
        int[] minDeque = new int[N];
        int maxHead = 0, maxTail = 0;
        int minHead = 0, minTail = 0;
        int left = 0;
        long lastShortTs = -1, lastLongTs = -1, lastTradeTs = -1;
        int skippedByTrend = 0;

        int warmup = strategy.windowMinutes * 60;

        for (int right = 0; right < N; right++) {
            long currentTs = timestamps[right];
            double currentHigh = highs[right];
            double currentLow = lows[right];

            // 单调队列：维护最高价
            while (maxHead < maxTail && highs[maxDeque[maxTail - 1]] <= currentHigh) {
                maxTail--;
            }
            maxDeque[maxTail++] = right;

            // 单调队列：维护最低价
            while (minHead < minTail && lows[minDeque[minTail - 1]] >= currentLow) {
                minTail--;
            }
            minDeque[minTail++] = right;

            // 滑动窗口左边界
            long windowStart = currentTs - windowUs;
            while (left < right && timestamps[left] < windowStart) {
                if (maxHead < maxTail && maxDeque[maxHead] == left) maxHead++;
                if (minHead < minTail && minDeque[minHead] == left) minHead++;
                left++;
            }

            if (right >= warmup && right - left >= 60 && maxHead < maxTail && minHead < minTail) {
                int maxIdx = maxDeque[maxHead];
                int minIdx = minDeque[minHead];
                long maxTs = timestamps[maxIdx];
                long minTs = timestamps[minIdx];
                double maxPrice = highs[maxIdx];
                double minPrice = lows[maxIdx];
                double currentPrice = closes[right];

                boolean trendUp = false, trendDown = false;
                if (strategy.useTrendFilter) {
                    long trendStart = currentTs - trendLookbackUs;
                    int trendIdx = java.util.Arrays.binarySearch(timestamps, 0, right, trendStart);
                    if (trendIdx < 0) trendIdx = -trendIdx - 1;
                    if (trendIdx < right) {
                        double priceOld = closes[trendIdx];
                        double change = (currentPrice - priceOld) / priceOld;
                        trendUp = change > strategy.trendThreshold;
                        trendDown = change < -strategy.trendThreshold;
                    }
                }

                boolean coolingOk = lastTradeTs == -1 || currentTs - lastTradeTs >= ONE_MINUTE_US;
                long maxGap = currentTs - maxTs;
                long minGap = currentTs - minTs;

                if (maxGap >= gapUs && maxGap <= gapUs + 5 * ONE_MINUTE_US
                        && currentTs > maxTs && maxTs != lastShortTs && coolingOk) {
                    if (!trendUp) {
                        TradeRecord trade = createTradeRecordFast(closes, settlePriceMap,
                                timestamps, right, "空单", maxPrice, minPrice,
                                maxTs, minTs, currentTs, settleUs);
                        tradeRecords.add(trade);
                        lastShortTs = maxTs;
                        lastTradeTs = currentTs;
                    } else {
                        skippedByTrend++;
                    }
                }

                if (minGap >= gapUs && minGap <= gapUs + 5 * ONE_MINUTE_US
                        && currentTs > minTs && minTs != lastLongTs && coolingOk) {
                    if (!trendDown) {
                        TradeRecord trade = createTradeRecordFast(closes, settlePriceMap,
                                timestamps, right, "多单", maxPrice, minPrice,
                                maxTs, minTs, currentTs, settleUs);
                        tradeRecords.add(trade);
                        lastLongTs = minTs;
                        lastTradeTs = currentTs;
                    } else {
                        skippedByTrend++;
                    }
                }
            }
        }

        return tradeRecords;
    }

    /**
     * 高速版创建交易记录（使用原始数组，避免parseDouble和Map<Long, EthKlineSecond>查询）
     */
    private TradeRecord createTradeRecordFast(double[] closes, Map<Long, Double> settlePriceMap,
                                               long[] timestamps, int openIdx, String direction,
                                               double high20min, double low20min,
                                               long maxTs, long minTs,
                                               long openTs, long settleUs) {
        TradeRecord record = new TradeRecord();
        record.setOpenTime(formatTimestamp(openTs));
        record.setOpenTimestamp(openTs);
        record.setDirection(direction);
        record.setOpenPrice(closes[openIdx]);
        record.setHigh20min(high20min);
        record.setLow20min(low20min);
        record.setHigh20minTime(formatTimestamp(maxTs));
        record.setLow20minTime(formatTimestamp(minTs));

        long targetTs = openTs + settleUs;
        Double settlePrice = settlePriceMap.get(targetTs);

        if (settlePrice != null) {
            record.setTenMinuteLaterPrice(settlePrice);
            record.setTenMinuteLaterTime(formatTimestamp(targetTs));
            double profit = "多单".equals(direction)
                    ? settlePrice - record.getOpenPrice()
                    : record.getOpenPrice() - settlePrice;
            record.setProfit(profit);
            record.setProfitPercent((profit / record.getOpenPrice()) * 100);
            record.setWin(profit > 0);
        }

        return record;
    }

    /**
     * 版本统计数据结构
     */
    private static class VersionStats {
        String name;
        int totalTrades;
        int longTrades;
        int shortTrades;
        int winCount;
        int loseCount;
        int skippedByTrend;
        int skippedByCooling;
        double realProfit;
        double fixedProfit;
        double winRate;
        long elapsedMs;
    }

    /**
     * 从交易记录列表构建版本统计数据
     */
    private VersionStats buildVersionStats(String name, List<TradeRecord> records, long elapsedMs) {
        VersionStats s = new VersionStats();
        s.name = name;
        s.totalTrades = records.size();
        s.longTrades = (int) records.stream().filter(t -> "多单".equals(t.getDirection())).count();
        s.shortTrades = s.totalTrades - s.longTrades;
        s.winCount = (int) records.stream().filter(TradeRecord::isWin).count();
        s.loseCount = s.totalTrades - s.winCount;
        s.realProfit = records.stream().mapToDouble(TradeRecord::getProfit).sum();
        s.fixedProfit = s.winCount * 4.0 - s.loseCount * 5.0;
        s.winRate = s.totalTrades > 0 ? s.winCount * 100.0 / s.totalTrades : 0;
        s.elapsedMs = elapsedMs;
        return s;
    }

    /**
     * 打印所有版本对比报告
     */
    private void printVersionComparison(List<VersionStats> statsList) {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                                     全版本策略回测对比报告                                            ║");
        System.out.println("╠════════┬────────┬──────┬──────┬──────┬──────┬──────────┬──────────┬──────────┬────────────╣");
        System.out.println("║ 版本   │ 总单数 │ 多单 │ 空单 │ 盈利 │ 亏损 │ 胜率     │ 真实盈亏 │ 固定盈亏 │ 趋势过滤   ║");
        System.out.println("╠════════┼────────┼──────┼──────┼──────┼──────┼──────────┼──────────┼──────────┼────────────╣");

        for (VersionStats s : statsList) {
            System.out.println(String.format("║ %-6s │ %6d │ %4d │ %4d │ %4d │ %4d │ %7.1f%% │ %+8.2f │ %+8.2f │ %4d       ║",
                    s.name, s.totalTrades, s.longTrades, s.shortTrades,
                    s.winCount, s.loseCount, s.winRate,
                    s.realProfit, s.fixedProfit, s.skippedByTrend));
        }

        System.out.println("╚════════╧════════╧══════╧══════╧══════╧══════╧══════════╧══════════╧══════════╧════════════╝");

        // 找出最佳版本
        VersionStats best = statsList.stream()
                .max(Comparator.comparingDouble(s -> s.fixedProfit))
                .orElse(null);
        VersionStats bestRate = statsList.stream()
                .max(Comparator.comparingDouble(s -> s.winRate))
                .orElse(null);

        if (best != null) {
            System.out.println("\n🏆 最佳收益版本: " + best.name + " (固定盈亏: " + String.format("%+.2fU", best.fixedProfit) + ")");
        }
        if (bestRate != null) {
            System.out.println("🏆 最高胜率版本: " + bestRate.name + " (胜率: " + String.format("%.1f%%", bestRate.winRate) + ")");
        }
    }

    /**
     * 保存全版本回测结果到Excel（每版本一个sheet + 汇总对比sheet）
     */
    private void saveAllVersionsToExcel(String filePath, VallisStrategy[] versions,
                                         List<List<TradeRecord>> allResults,
                                         List<VersionStats> allStats, long totalElapsed) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            // 汇总对比sheet
            SXSSFSheet summarySheet = workbook.createSheet("汇总对比");
            createSummarySheet(workbook, summarySheet, versions, allStats, totalElapsed);

            // 各版本交易明细sheet
            for (int i = 0; i < versions.length; i++) {
                String sheetName = versions[i].name().toLowerCase();
                SXSSFSheet dataSheet = workbook.createSheet(sheetName);
                createDataSheet(workbook, dataSheet, allResults.get(i));

                SXSSFSheet statsSheet = workbook.createSheet(sheetName + "_统计");
                createStatsSheet(workbook, statsSheet, allResults.get(i));
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }
        }
    }

    /**
     * 诊断胜率低的根本原因
     * 分析极值信号出现后，价格是否真的会回归（均值回归假设是否成立）
     */
    @Test
    public void testDiagnoseWinRate() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              胜率诊断分析 - 极值信号有效性验证                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // 加载数据
        System.out.println("[1/3] 加载数据...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        boolean isMicro = !sample.isEmpty() && sample.get(0).getTimestamp() > 1000000000000000L;
        long startMs = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long startTs = isMicro ? startMs * 1000 : startMs;
        long endTs = isMicro ? System.currentTimeMillis() * 1000 : System.currentTimeMillis();
        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(startTs, endTs);
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        int N = allData.size();

        System.out.println("[2/3] 预计算...");
        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];
        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
        }
        allData = null;
        System.gc();

        // 分析不同窗口大小
        int[] windowMinutes = {15, 20, 30, 120};
        int[] settleMinutes = {10, 30, 60};

        System.out.println("\n[3/3] 诊断分析...\n");
        System.out.println("┌──────────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  核心问题：极值出现后，价格是均值回归（空高点多低点）还是趋势延续（空低点多高点）？       │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────────┘\n");

        for (int wm : windowMinutes) {
            long windowUs = wm * 60L * 1000000L;
            long gapUs = (wm <= 20) ? 3 * 60L * 1000000L : 1 * 60L * 1000000L;

            System.out.println("═══════════════════════════════════════════════════");
            System.out.println("  窗口: " + wm + "分钟  |  极值间隙: " + (wm <= 20 ? 3 : 1) + "分钟");
            System.out.println("═══════════════════════════════════════════════════");

            // 收集所有极值事件
            int[] maxDeque = new int[N], minDeque = new int[N];
            int maxH = 0, maxT = 0, minH = 0, minT = 0;
            int left = 0;
            int warmup = wm * 60;

            List<ExtremeEvent> events = new ArrayList<>();

            for (int right = 0; right < N; right++) {
                // 维护极值队列
                while (maxH < maxT && highs[maxDeque[maxT - 1]] <= highs[right]) maxT--;
                maxDeque[maxT++] = right;
                while (minH < minT && lows[minDeque[minT - 1]] >= lows[right]) minT--;
                minDeque[minT++] = right;

                long ws = timestamps[right] - windowUs;
                while (left < right && timestamps[left] < ws) {
                    if (maxH < maxT && maxDeque[maxH] == left) maxH++;
                    if (minH < minT && minDeque[minH] == left) minH++;
                    left++;
                }

                if (right >= warmup && maxH < maxT && minH < minT) {
                    int maxIdx = maxDeque[maxH];
                    int minIdx = minDeque[minH];
                    long maxTs = timestamps[maxIdx];
                    long minTs = timestamps[minIdx];
                    long currentTs = timestamps[right];
                    long maxGap = currentTs - maxTs;
                    long minGap = currentTs - minTs;

                    if (maxGap >= gapUs && maxGap <= gapUs + 5 * 60L * 1000000L) {
                        events.add(new ExtremeEvent(maxTs, "high", highs[maxIdx], closes[right], right, maxGap));
                    }
                    if (minGap >= gapUs && minGap <= gapUs + 5 * 60L * 1000000L) {
                        events.add(new ExtremeEvent(minTs, "low", lows[minIdx], closes[right], right, minGap));
                    }
                }
            }

            // 分析每个极值事件后各时间点的价格走向
            for (int sm : settleMinutes) {
                long settleUs = sm * 60L * 1000000L;
                int highTotal = 0, highUp = 0, highDown = 0;
                int lowTotal = 0, lowUp = 0, lowDown = 0;

                for (ExtremeEvent e : events) {
                    long targetTs = e.ts + settleUs;
                    int idx = java.util.Arrays.binarySearch(timestamps, e.rightIdx, N, targetTs);
                    if (idx < 0) idx = -idx - 1;
                    if (idx >= N) continue;

                    double settlePrice = closes[idx];
                    double change = (settlePrice - e.atPrice) / e.atPrice * 100;

                    if ("high".equals(e.type)) {
                        highTotal++;
                        if (settlePrice > e.atPrice) highUp++;
                        else highDown++;
                    } else {
                        lowTotal++;
                        if (settlePrice > e.atPrice) lowUp++;
                        else lowDown++;
                    }
                }

                // 策略逻辑：高极值→开空单（赌下跌），低极值→开多单（赌上涨）
                // 所以 highDown + lowUp = 策略正确方向
                int correct = highDown + lowUp;
                int total = highTotal + lowTotal;

                System.out.println(String.format("  ▸ 结算 %d分钟后:", sm));
                System.out.println(String.format("    高极值: %d次 → 后续上涨 %d次(%.1f%%) | 下跌 %d次(%.1f%%)  [策略赌下跌]",
                        highTotal, highUp, highTotal > 0 ? highUp * 100.0 / highTotal : 0,
                        highDown, highTotal > 0 ? highDown * 100.0 / highTotal : 0));
                System.out.println(String.format("    低极值: %d次 → 后续上涨 %d次(%.1f%%) | 下跌 %d次(%.1f%%)  [策略赌上涨]",
                        lowTotal, lowUp, lowTotal > 0 ? lowUp * 100.0 / lowTotal : 0,
                        lowDown, lowTotal > 0 ? lowDown * 100.0 / lowTotal : 0));
                System.out.println(String.format("    ★ 策略胜率: %d/%d = %.1f%%  (高极值下跌 + 低极值上涨 = 正确方向)",
                        correct, total, total > 0 ? correct * 100.0 / total : 0));
                System.out.println();
            }
        }

        System.out.println("┌──────────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  结论：如果策略胜率 ≈ 50%，说明价格在极值后是随机游走，均值回归假设不成立               │");
        System.out.println("│  如果高极值后上涨概率 > 50%，说明市场是趋势性的，极值是趋势信号而非反转信号               │");
        System.out.println("└──────────────────────────────────────────────────────────────────────────────┘");
    }

    /** 极值事件（用于诊断） */
    private static class ExtremeEvent {
        long ts;
        String type; // "high" or "low"
        double extremePrice;
        double atPrice; // 触发时的当前价格
        int rightIdx;   // 触发时的数组索引
        long gap;

        ExtremeEvent(long ts, String type, double extremePrice, double atPrice, int rightIdx, long gap) {
            this.ts = ts;
            this.type = type;
            this.extremePrice = extremePrice;
            this.atPrice = atPrice;
            this.rightIdx = rightIdx;
            this.gap = gap;
        }
    }

    /**
     * 创建全版本汇总对比sheet
     */
    private void createSummarySheet(SXSSFWorkbook workbook, SXSSFSheet sheet,
                                     VallisStrategy[] versions, List<VersionStats> statsList,
                                     long totalElapsed) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        CellStyle greenStyle = workbook.createCellStyle();
        Font greenFont = workbook.createFont();
        greenFont.setColor(IndexedColors.GREEN.getIndex());
        greenStyle.setFont(greenFont);

        CellStyle redStyle = workbook.createCellStyle();
        Font redFont = workbook.createFont();
        redFont.setColor(IndexedColors.RED.getIndex());
        redStyle.setFont(redFont);

        int rowNum = 0;
        Row titleRow = sheet.createRow(rowNum++);
        titleRow.createCell(0).setCellValue("全版本策略回测对比报告");
        titleRow.getCell(0).setCellStyle(headerStyle);
        rowNum++;

        sheet.createRow(rowNum++).createCell(0).setCellValue("总耗时: " + formatTime(totalElapsed));
        rowNum++;

        String[] headers = {"版本", "窗口(分)", "结算(分)", "极值间隙(分)", "趋势过滤", "总单数",
                "多单", "空单", "盈利", "亏损", "胜率(%)", "真实盈亏", "固定盈亏(4U/5U)"};
        Row headerRow = sheet.createRow(rowNum++);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        for (int vi = 0; vi < versions.length; vi++) {
            VallisStrategy ver = versions[vi];
            VersionStats s = statsList.get(vi);
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(ver.name);
            row.createCell(1).setCellValue(ver.windowMinutes);
            row.createCell(2).setCellValue(ver.settleMinutes);
            row.createCell(3).setCellValue(ver.gapMinutes);
            row.createCell(4).setCellValue(ver.useTrendFilter ? String.format("%.1f%%", ver.trendThreshold * 100) : "无");
            row.createCell(5).setCellValue(s.totalTrades);
            row.createCell(6).setCellValue(s.longTrades);
            row.createCell(7).setCellValue(s.shortTrades);
            row.createCell(8).setCellValue(s.winCount);
            row.createCell(9).setCellValue(s.loseCount);
            row.createCell(10).setCellValue(String.format("%.1f", s.winRate));

            Cell profitCell = row.createCell(11);
            profitCell.setCellValue(s.realProfit);
            profitCell.setCellStyle(s.realProfit >= 0 ? greenStyle : redStyle);

            Cell fixedCell = row.createCell(12);
            fixedCell.setCellValue(s.fixedProfit);
            fixedCell.setCellStyle(s.fixedProfit >= 0 ? greenStyle : redStyle);
        }

        for (int i = 0; i < headers.length; i++) sheet.setColumnWidth(i, 4000);
    }

    /**
     * 执行策略回测全流程
     * 流程：加载数据 → 排序验证 → 构建索引 → 执行策略 → 输出统计 → 保存Excel
     *
     * @param strategy 策略枚举（包含所有参数）
     */
    private void runBacktest(VallisStrategy strategy) throws IOException {
        long windowUs = strategy.windowMinutes * 60L * 1000000L;
        long settleUs = strategy.settleMinutes * 60L * 1000000L;
        long gapUs = strategy.gapMinutes * 60L * 1000000L;

        System.out.println("======================================");
        System.out.println("    " + strategy.name + " 回测");
        System.out.println("======================================");
        long startTime = System.currentTimeMillis();

        System.out.println("\n[1/4] 正在加载数据（2026-06-1 至今）...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        boolean isMicro = !sample.isEmpty() && sample.get(0).getTimestamp() > 1000000000000000L;
        long startMs = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long startTs = isMicro ? startMs * 1000 : startMs;
        long endTs = isMicro ? System.currentTimeMillis() * 1000 : System.currentTimeMillis();
        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(startTs, endTs);

        if (allData.isEmpty()) {
            System.out.println("数据库中没有数据");
            return;
        }
        System.out.println("数据加载完成，共 " + formatNumber(allData.size()) + " 条");
        System.out.println("时间戳格式: " + (isMicro ? "微秒级" : "毫秒级"));

        System.out.println("\n[2/4] 正在排序数据...");
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));

        System.out.println("[2.5/4] 正在验证时间连续性...");
        validateTimeContinuity(allData);

        System.out.println("[3/4] 正在构建索引...");
        Map<Long, EthKlineSecond> dataMap = new HashMap<>(allData.size());
        List<Long> timestamps = new ArrayList<>(allData.size());
        for (EthKlineSecond data : allData) {
            dataMap.put(data.getTimestamp(), data);
            timestamps.add(data.getTimestamp());
        }

        System.out.println("\n[4/4] 正在执行策略回测...");
        List<TradeRecord> tradeRecords = executeSlidingWindowStrategy(allData, dataMap, timestamps, strategy, windowUs, settleUs, gapUs);

        long endTime = System.currentTimeMillis();
        printStatistics(tradeRecords, endTime - startTime);

        String outputPath = "D:\\trading_backtest_" + strategy.name().toLowerCase() + ".xlsx";
        saveToExcel(tradeRecords, outputPath);
        System.out.println("\n✅ 结果已保存到: " + outputPath);
        System.out.println("\n======================================");
        System.out.println("    " + strategy.name + " 回测完成");
        System.out.println("======================================");
    }

    /**
     * 执行滑动窗口策略（参数化版本）
     *
     * 核心算法：
     * 1. 使用两个单调队列维护窗口内最大值/最小值索引（O(n)复杂度）
     * 2. 滑动窗口右指针遍历所有数据，左指针维护窗口边界
     * 3. 趋势过滤：5分钟涨跌幅超过阈值视为有效趋势
     * 4. 极值去重：记录已使用的极值时间戳，避免重复开单
     * 5. 冷却控制：lastTradeTs记录上次开单时间，一分钟内不重复开单
     *
     * @param strategy 策略参数
     * @param windowUs 极值窗口（微秒）
     * @param settleUs 结算时间（微秒）
     */
    private List<TradeRecord> executeSlidingWindowStrategy(List<EthKlineSecond> allData,
                                                           Map<Long, EthKlineSecond> dataMap,
                                                           List<Long> timestamps,
                                                           VallisStrategy strategy,
                                                           long windowUs, long settleUs, long gapUs) {
        List<TradeRecord> tradeRecords = new ArrayList<>();
        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        int left = 0;
        long lastShortTs = -1;
        long lastLongTs = -1;
        long lastTradeTs = -1;
        int skippedByTrend = 0;
        int skippedByGap = 0;

        long trendLookbackUs = 5 * 60L * 1000000L;

        for (int right = 0; right < allData.size(); right++) {
            EthKlineSecond currentData = allData.get(right);
            long currentTs = currentData.getTimestamp();

            // 单调队列维护窗口内最高价(high)和最低价(low)
            while (!maxDeque.isEmpty() &&
                    parseDouble(allData.get(maxDeque.peekLast()).getHigh()) <= parseDouble(currentData.getHigh())) {
                maxDeque.pollLast();
            }
            maxDeque.offerLast(right);

            while (!minDeque.isEmpty() &&
                    parseDouble(allData.get(minDeque.peekLast()).getLow()) >= parseDouble(currentData.getLow())) {
                minDeque.pollLast();
            }
            minDeque.offerLast(right);

            long windowStartTime = currentTs - windowUs;
            while (timestamps.get(left) < windowStartTime) {
                if (maxDeque.peekFirst() == left) maxDeque.pollFirst();
                if (minDeque.peekFirst() == left) minDeque.pollFirst();
                left++;
            }

            int warmupSeconds = strategy.windowMinutes * 60;
            if (right >= warmupSeconds && right - left >= 60 && !maxDeque.isEmpty() && !minDeque.isEmpty()) {
                int maxIndex = maxDeque.peekFirst();
                int minIndex = minDeque.peekFirst();
                long maxTs = timestamps.get(maxIndex);
                long minTs = timestamps.get(minIndex);
                double maxPrice = parseDouble(allData.get(maxIndex).getHigh());
                double minPrice = parseDouble(allData.get(minIndex).getLow());
                double currentPrice = parseDouble(currentData.getClose());

                boolean trendUp = false, trendDown = false;
                if (strategy.useTrendFilter) {
                    long trendStart = currentTs - trendLookbackUs;
                    int trendStartIdx = Collections.binarySearch(timestamps, trendStart);
                    if (trendStartIdx < 0) trendStartIdx = -trendStartIdx - 1;
                    if (trendStartIdx < right) {
                        double priceOld = parseDouble(allData.get(trendStartIdx).getClose());
                        double change = (currentPrice - priceOld) / priceOld;
                        trendUp = change > strategy.trendThreshold;
                        trendDown = change < -strategy.trendThreshold;
                    }
                }

                boolean coolingOk = lastTradeTs == -1
                        || currentTs - lastTradeTs >= ONE_MINUTE_US;

                long maxGap = currentTs - maxTs;
                long minGap = currentTs - minTs;

                if (maxGap >= gapUs && maxGap <= gapUs + 5 * ONE_MINUTE_US
                        && currentTs > maxTs && maxTs != lastShortTs && coolingOk) {
                    if (trendUp) {
                        skippedByTrend++;
                    } else {
                        TradeRecord trade = createTradeRecord(currentData, "空单", maxPrice, minPrice,
                                formatTimestamp(maxTs), formatTimestamp(minTs), dataMap, currentTs, settleUs);
                        tradeRecords.add(trade);
                        lastShortTs = maxTs;
                        lastTradeTs = currentTs;
                    }
                }

                if (minGap >= gapUs && minGap <= gapUs + 5 * ONE_MINUTE_US
                        && currentTs > minTs && minTs != lastLongTs && coolingOk) {
                    if (trendDown) {
                        skippedByTrend++;
                    } else {
                        TradeRecord trade = createTradeRecord(currentData, "多单", maxPrice, minPrice,
                                formatTimestamp(maxTs), formatTimestamp(minTs), dataMap, currentTs, settleUs);
                        tradeRecords.add(trade);
                        lastLongTs = minTs;
                        lastTradeTs = currentTs;
                    }
                }
            }

            if ((right + 1) % 100000 == 0) {
                System.out.println("  处理进度: " + formatNumber(right + 1) + "/" + formatNumber(allData.size()) +
                        " (" + String.format("%.1f%%", ((right + 1) * 100.0 / allData.size())) + ")" +
                        " 已生成:" + tradeRecords.size() + " 趋势过滤:" + skippedByTrend);
            }
        }

        System.out.println("  趋势过滤跳过: " + skippedByTrend + "  gap跳过: " + skippedByGap);
        return tradeRecords;
    }

    /**
     * 创建交易记录，计算10分钟后的结算价与盈亏
     *
     * @param openData 开单时的K线数据
     * @param direction 方向（多单/空单）
     * @param dataMap 时间戳到K线数据的映射
     * @param openTs 开单时间戳
     * @param settleUs 结算时间偏移（微秒）
     */
    private TradeRecord createTradeRecord(EthKlineSecond openData, String direction,
                                          double high20min, double low20min,
                                          String high20minTime, String low20minTime,
                                          Map<Long, EthKlineSecond> dataMap, long openTs, long settleUs) {
        TradeRecord record = new TradeRecord();
        record.setOpenTime(formatTimestamp(openTs));
        record.setOpenTimestamp(openTs);
        record.setDirection(direction);
        record.setOpenPrice(parseDouble(openData.getClose()));
        record.setHigh20min(high20min);
        record.setLow20min(low20min);
        record.setHigh20minTime(high20minTime);
        record.setLow20minTime(low20minTime);

        long targetTs = openTs + settleUs;
        EthKlineSecond targetData = dataMap.get(targetTs);

        if (targetData != null) {
            record.setTenMinuteLaterPrice(parseDouble(targetData.getClose()));
            record.setTenMinuteLaterTime(formatTimestamp(targetTs));
            double profit = "多单".equals(direction)
                    ? record.getTenMinuteLaterPrice() - record.getOpenPrice()
                    : record.getOpenPrice() - record.getTenMinuteLaterPrice();
            record.setProfit(profit);
            record.setProfitPercent((profit / record.getOpenPrice()) * 100);
            record.setWin(profit > 0);
        }

        return record;
    }

    /**
     * 验证K线数据时间连续性
     * 检查相邻数据点间隔是否为1秒，发现异常间隔打印警告
     */
    private void validateTimeContinuity(List<EthKlineSecond> data) {
        if (data.isEmpty()) {
            System.out.println("  ⚠️ 数据为空，跳过时间连续性验证");
            return;
        }

        int gaps = 0;
        long prevTs = data.get(0).getTimestamp();
        long expectedInterval = 1000000L; // 秒级数据，间隔应为1秒（微秒级）

        for (int i = 1; i < data.size(); i++) {
            long currentTs = data.get(i).getTimestamp();
            long diff = currentTs - prevTs;

            if (diff != expectedInterval) {
                gaps++;
                if (gaps <= 5) { // 只显示前5个间隔异常
                    System.out.println("  ⚠️ 时间间隔异常: 位置=" + i + 
                            ", 前一时间=" + formatTimestamp(prevTs) + 
                            ", 当前时间=" + formatTimestamp(currentTs) + 
                            ", 间隔=" + (diff / 1000000.0) + "秒");
                }
            }
            prevTs = currentTs;
        }

        if (gaps == 0) {
            System.out.println("  ✅ 时间连续性验证通过，数据连续");
        } else {
            System.out.println("  ⚠️ 时间连续性验证完成，发现 " + gaps + " 处时间间隔异常");
        }

        // 计算总天数
        long firstTs = data.get(0).getTimestamp();
        long lastTs = data.get(data.size() - 1).getTimestamp();
        double days = (lastTs - firstTs) / (1000000.0 * 60 * 60 * 24);
        System.out.println("  📅 数据覆盖时间: " + String.format("%.2f", days) + " 天");
    }

    /**
     * 打印回测统计报告
     * 包含：交易总数、多空分布、每日开单统计、胜率、收益统计、固定收益
     */
    private void printStatistics(List<TradeRecord> records, long elapsedTime) {
        if (records.isEmpty()) {
            System.out.println("\n⚠️ 没有生成任何交易记录");
            return;
        }

        List<TradeRecord> longTrades = records.stream()
                .filter(t -> "多单".equals(t.getDirection()))
                .collect(Collectors.toList());

        List<TradeRecord> shortTrades = records.stream()
                .filter(t -> "空单".equals(t.getDirection()))
                .collect(Collectors.toList());

        double totalProfit = records.stream().mapToDouble(TradeRecord::getProfit).sum();
        double avgProfit = records.stream().mapToDouble(TradeRecord::getProfit).average().orElse(0);
        double maxProfit = records.stream().mapToDouble(TradeRecord::getProfit).max().orElse(0);
        double minProfit = records.stream().mapToDouble(TradeRecord::getProfit).min().orElse(0);
        long winCount = records.stream().filter(TradeRecord::isWin).count();
        long loseCount = records.size() - winCount;

        // 固定收益计算：盈利+4U，亏损-5U
        double fixedTotalProfit = winCount * 4.0 - loseCount * 5.0;
        double fixedAvgProfit = records.isEmpty() ? 0 : fixedTotalProfit / records.size();

        // 统计每日开单数量
        Map<String, Long> dailyCounts = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getOpenTime().substring(0, 10), // 提取日期部分
                        Collectors.counting()
                ));

        double avgDailyTrades = dailyCounts.values().stream().mapToLong(Long::longValue).average().orElse(0);
        long maxDailyTrades = dailyCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long minDailyTrades = dailyCounts.values().stream().mapToLong(Long::longValue).min().orElse(0);

        System.out.println("\n📊 策略回测统计报告");
        System.out.println("──────────────────────────────────────");
        System.out.println("总耗时: " + formatTime(elapsedTime));
        System.out.println("──────────────────────────────────────");
        System.out.println("交易总数: " + formatNumber(records.size()));
        System.out.println("  ├─ 多单: " + formatNumber(longTrades.size()));
        System.out.println("  └─ 空单: " + formatNumber(shortTrades.size()));
        System.out.println("──────────────────────────────────────");
        System.out.println("每日开单统计:");
        System.out.println("  ├─ 平均每日开单: " + String.format("%.1f", avgDailyTrades) + " 单");
        System.out.println("  ├─ 最多每日开单: " + formatNumber(maxDailyTrades) + " 单");
        System.out.println("  └─ 最少每日开单: " + formatNumber(minDailyTrades) + " 单");
        System.out.println("──────────────────────────────────────");
        System.out.println("胜率: " + String.format("%.2f%%", (winCount * 100.0 / records.size())));
        System.out.println("──────────────────────────────────────");
        System.out.println("收益统计:");
        System.out.println("  ├─ 总收益: $" + String.format("%.2f", totalProfit));
        System.out.println("  ├─ 平均收益: $" + String.format("%.2f", avgProfit));
        System.out.println("  ├─ 最大盈利: $" + String.format("%.2f", maxProfit));
        System.out.println("  └─ 最大亏损: $" + String.format("%.2f", minProfit));
        System.out.println("──────────────────────────────────────");
        System.out.println("固定收益统计 (盈利+4U, 亏损-5U):");
        System.out.println("  ├─ 盈利次数: " + winCount + " × 4U = +" + String.format("%.2f", winCount * 4.0) + "U");
        System.out.println("  ├─ 亏损次数: " + loseCount + " × 5U = -" + String.format("%.2f", loseCount * 5.0) + "U");
        System.out.println("  ├─ 固定总收益: " + (fixedTotalProfit >= 0 ? "+" : "") + String.format("%.2f", fixedTotalProfit) + "U");
        System.out.println("  └─ 固定平均收益: " + String.format("%.2f", fixedAvgProfit) + "U/单");
        System.out.println("──────────────────────────────────────");
    }

    /**
     * 保存回测结果到Excel文件（含交易记录页 + 统计汇总页）
     * 使用SXSSF流式写入，避免大文件OOM
     *
     * @param records  交易记录列表
     * @param filePath 输出文件路径
     */
    private void saveToExcel(List<TradeRecord> records, String filePath) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            SXSSFSheet dataSheet = workbook.createSheet("交易记录");
            createDataSheet(workbook, dataSheet, records);

            SXSSFSheet statsSheet = workbook.createSheet("统计汇总");
            createStatsSheet(workbook, statsSheet, records);

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }
        }
    }

    /**
     * 创建交易记录明细页，包含方向、开单价格、结算价格、利润等字段
     */
    private void createDataSheet(SXSSFWorkbook workbook, SXSSFSheet sheet, List<TradeRecord> records) throws IOException {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        CellStyle winStyle = workbook.createCellStyle();
        Font winFont = workbook.createFont();
        winFont.setColor(IndexedColors.GREEN.getIndex());
        winStyle.setFont(winFont);

        CellStyle loseStyle = workbook.createCellStyle();
        Font loseFont = workbook.createFont();
        loseFont.setColor(IndexedColors.RED.getIndex());
        loseStyle.setFont(loseFont);

        // 基于最高价开单（空单）行背景色：浅红色
        CellStyle shortRowStyle = workbook.createCellStyle();
        shortRowStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        shortRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // 基于最低价开单（多单）行背景色：浅绿色
        CellStyle longRowStyle = workbook.createCellStyle();
        longRowStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        longRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Row headerRow = sheet.createRow(0);
        String[] headers = {"序号", "开单时刻", "开单方向", "开单价格", 
                "20分钟内最高价", "最高价时间", "20分钟内最低价", "最低价时间",
                "10分钟后时刻", "10分钟后价格", "收益", "收益率(%)", "盈亏"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 1;
        int seq = 1;
        for (TradeRecord record : records) {
            Row row = sheet.createRow(rowNum++);
            // 根据开单方向设置行背景色
            CellStyle rowStyle = "空单".equals(record.getDirection()) ? shortRowStyle : longRowStyle;

            for (int i = 0; i < headers.length; i++) {
                Cell cell = row.createCell(i);
                cell.setCellStyle(rowStyle);
            }

            row.getCell(0).setCellValue(seq++);
            row.getCell(1).setCellValue(record.getOpenTime());
            row.getCell(2).setCellValue(record.getDirection());
            row.getCell(3).setCellValue(record.getOpenPrice());
            row.getCell(4).setCellValue(record.getHigh20min());
            row.getCell(5).setCellValue(record.getHigh20minTime() != null ? record.getHigh20minTime() : "-");
            row.getCell(6).setCellValue(record.getLow20min());
            row.getCell(7).setCellValue(record.getLow20minTime() != null ? record.getLow20minTime() : "-");
            row.getCell(8).setCellValue(record.getTenMinuteLaterTime() != null ? record.getTenMinuteLaterTime() : "-");
            row.getCell(9).setCellValue(record.getTenMinuteLaterPrice());

            Cell profitCell = row.getCell(10);
            profitCell.setCellValue(record.getProfit());
            profitCell.setCellStyle(record.isWin() ? winStyle : loseStyle);

            Cell percentCell = row.getCell(11);
            percentCell.setCellValue(String.format("%.2f", record.getProfitPercent()));
            percentCell.setCellStyle(record.isWin() ? winStyle : loseStyle);

            row.getCell(12).setCellValue(record.isWin() ? "盈利" : "亏损");

            if (rowNum % BATCH_WRITE_SIZE == 0) {
                sheet.flushRows();
            }
        }

        for (int i = 0; i < headers.length; i++) sheet.setColumnWidth(i, 4000);
        sheet.setColumnWidth(1, 6000);
        sheet.setColumnWidth(5, 6000);
        sheet.setColumnWidth(7, 6000);
        sheet.setColumnWidth(8, 6000);
    }

    /**
     * 创建统计汇总页，包含胜率、收益分布、多空统计、每日统计等
     */
    private void createStatsSheet(SXSSFWorkbook workbook, SXSSFSheet sheet, List<TradeRecord> records) throws IOException {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        List<TradeRecord> longTrades = records.stream()
                .filter(t -> "多单".equals(t.getDirection()))
                .collect(Collectors.toList());

        int rowNum = 0;
        Row titleRow = sheet.createRow(rowNum++);
        titleRow.createCell(0).setCellValue("交易策略回测统计汇总");
        titleRow.getCell(0).setCellStyle(headerStyle);

        rowNum++;
        sheet.createRow(rowNum++).createCell(0).setCellValue("交易总数: " + records.size());
        sheet.createRow(rowNum++).createCell(0).setCellValue("多单数量: " + longTrades.size());
        sheet.createRow(rowNum++).createCell(0).setCellValue("空单数量: " + (records.size() - longTrades.size()));

        rowNum++;
        long winCount = records.stream().filter(TradeRecord::isWin).count();
        sheet.createRow(rowNum++).createCell(0).setCellValue("胜率: " + String.format("%.2f%%", (winCount * 100.0 / records.size())));

        rowNum++;
        double totalProfit = records.stream().mapToDouble(TradeRecord::getProfit).sum();
        sheet.createRow(rowNum++).createCell(0).setCellValue("总收益: $" + String.format("%.2f", totalProfit));
        sheet.createRow(rowNum++).createCell(0).setCellValue("平均收益: $" + String.format("%.2f",
                records.stream().mapToDouble(TradeRecord::getProfit).average().orElse(0)));

        rowNum++;
        long loseCount = records.size() - winCount;
        double fixedTotalProfit = winCount * 4.0 - loseCount * 5.0;
        sheet.createRow(rowNum++).createCell(0).setCellValue("固定收益统计 (盈利+4U, 亏损-5U):");
        sheet.createRow(rowNum++).createCell(0).setCellValue("  盈利次数: " + winCount + " × 4U = +" + String.format("%.2f", winCount * 4.0) + "U");
        sheet.createRow(rowNum++).createCell(0).setCellValue("  亏损次数: " + loseCount + " × 5U = -" + String.format("%.2f", loseCount * 5.0) + "U");
        sheet.createRow(rowNum++).createCell(0).setCellValue("  固定总收益: " + (fixedTotalProfit >= 0 ? "+" : "") + String.format("%.2f", fixedTotalProfit) + "U");

        sheet.setColumnWidth(0, 8000);
    }

    /** 格式化时间戳（兼容毫秒/微秒两种格式）*/
    private String formatTimestamp(long timestamp) {
        long ts = timestamp > 1000000000000000L ? timestamp / 1000 : timestamp;
        Date date = new Date(ts);
        return String.format("%tF %tT", date, date);
    }

    /** 安全解析Double字符串，处理null和逗号 */
    private double parseDouble(String value) {
        try {
            if (value == null) return 0;
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 格式化数字（千分位分隔）*/
    private String formatNumber(long num) {
        return String.format("%,d", num);
    }

    /** 格式化耗时（毫秒转可读格式）*/
    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return String.format("%d小时%d分钟%d秒", hours, minutes % 60, seconds % 60);
        if (minutes > 0) return String.format("%d分钟%d秒", minutes, seconds % 60);
        return String.format("%d毫秒", millis);
    }

    /**
     * 含冷静期回测
     * 满足开单条件后，等待1分钟确认价格不再突破极值才开单
     * 最长等待15分钟，超时则取消开单
     */
    @Test
    public void testTradingStrategyWithCooling() throws IOException {
        System.out.println("======================================");
        System.out.println("  交易策略回测(含冷静期)");
        System.out.println("======================================");
        long startTime = System.currentTimeMillis();

        System.out.println("\n[1/4] 正在加载数据...");
        List<EthKlineSecond> allData = ethKlineSecondMapper.selectRecent(3000000);
        if (allData.isEmpty()) {
            System.out.println("数据库中没有数据");
            return;
        }
        System.out.println("数据加载完成，共 " + formatNumber(allData.size()) + " 条");

        System.out.println("\n[2/4] 正在排序数据...");
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));

        System.out.println("\n[3/4] 正在构建索引...");
        Map<Long, EthKlineSecond> dataMap = new HashMap<>(allData.size());
        List<Long> timestamps = new ArrayList<>(allData.size());
        for (EthKlineSecond data : allData) {
            dataMap.put(data.getTimestamp(), data);
            timestamps.add(data.getTimestamp());
        }

        System.out.println("\n[4/4] 正在执行策略回测(含冷静期)...");
        List<TradeRecord> tradeRecords = executeSlidingWindowStrategyWithCooling(allData, dataMap, timestamps);

        long endTime = System.currentTimeMillis();
        printStatistics(tradeRecords, endTime - startTime);

        String outputPath = "D:\\trading_backtest_cooling_result.xlsx";
        saveToExcel(tradeRecords, outputPath);
        System.out.println("\n✅ 结果已保存到: " + outputPath);
        System.out.println("\n======================================");
        System.out.println("  交易策略回测(含冷静期)完成");
        System.out.println("======================================");
    }

    /**
     * 执行含冷静期的滑动窗口策略
     * 与基础策略逻辑相同，但开单时增加冷静期判断
     */
    private List<TradeRecord> executeSlidingWindowStrategyWithCooling(List<EthKlineSecond> allData,
                                                                       Map<Long, EthKlineSecond> dataMap,
                                                                       List<Long> timestamps) {
        List<TradeRecord> tradeRecords = new ArrayList<>();
        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        int left = 0;
        long lastShortTs = -1;
        long lastLongTs = -1;
        long lastTradeTs = -1;
        int skippedByCooling = 0;

        for (int right = 0; right < allData.size(); right++) {
            EthKlineSecond currentData = allData.get(right);
            long currentTs = currentData.getTimestamp();
            double currentPrice = parseDouble(currentData.getClose());

            while (!maxDeque.isEmpty() &&
                    parseDouble(allData.get(maxDeque.peekLast()).getClose()) <= currentPrice) {
                maxDeque.pollLast();
            }
            maxDeque.offerLast(right);

            while (!minDeque.isEmpty() &&
                    parseDouble(allData.get(minDeque.peekLast()).getClose()) >= currentPrice) {
                minDeque.pollLast();
            }
            minDeque.offerLast(right);

            long windowStartTime = currentTs - TWENTY_MINUTES_US;
            while (timestamps.get(left) < windowStartTime) {
                if (maxDeque.peekFirst() == left) maxDeque.pollFirst();
                if (minDeque.peekFirst() == left) minDeque.pollFirst();
                left++;
            }

            if (right >= 1200 && right - left >= 60 && !maxDeque.isEmpty() && !minDeque.isEmpty()) {
                int maxIndex = maxDeque.peekFirst();
                int minIndex = minDeque.peekFirst();
                long maxTs = timestamps.get(maxIndex);
                long minTs = timestamps.get(minIndex);
                double maxPrice = parseDouble(allData.get(maxIndex).getClose());
                double minPrice = parseDouble(allData.get(minIndex).getClose());

                double[] hl = computeWindowHighLow(allData, left, right);
                double windowMaxHigh = hl[0];
                double windowMinLow = hl[1];

                if (currentTs - maxTs >= ONE_MINUTE_US && currentTs - maxTs <= THREE_MINUTES_US
                        && currentTs > maxTs && maxTs != lastShortTs
                        && (lastTradeTs == -1 || currentTs - lastTradeTs >= ONE_MINUTE_US)) {
                    TradeRecord trade = createTradeRecordWithCooling(allData, right, "空单",
                            maxPrice, minPrice, windowMaxHigh, windowMinLow,
                            formatTimestamp(maxTs), formatTimestamp(minTs), dataMap, currentTs);
                    if (trade != null) {
                        tradeRecords.add(trade);
                        lastShortTs = maxTs;
                        lastTradeTs = trade.getDelayedOpenTimestamp();
                    } else {
                        skippedByCooling++;
                    }
                }

                if (currentTs - minTs >= ONE_MINUTE_US && currentTs - minTs <= THREE_MINUTES_US
                        && currentTs > minTs && minTs != lastLongTs
                        && (lastTradeTs == -1 || currentTs - lastTradeTs >= ONE_MINUTE_US)) {
                    TradeRecord trade = createTradeRecordWithCooling(allData, right, "多单",
                            maxPrice, minPrice, windowMaxHigh, windowMinLow,
                            formatTimestamp(maxTs), formatTimestamp(minTs), dataMap, currentTs);
                    if (trade != null) {
                        tradeRecords.add(trade);
                        lastLongTs = minTs;
                        lastTradeTs = trade.getDelayedOpenTimestamp();
                    } else {
                        skippedByCooling++;
                    }
                }
            }

            if ((right + 1) % 100000 == 0) {
                System.out.println("  处理进度: " + formatNumber(right + 1) + "/" + formatNumber(allData.size()) +
                        " (" + String.format("%.1f%%", ((right + 1) * 100.0 / allData.size())) + "%)" +
                        " 已生成:" + tradeRecords.size() + " 取消:" + skippedByCooling);
            }
        }

        System.out.println("  冷静期取消开单数: " + skippedByCooling);
        return tradeRecords;
    }

    /** 计算窗口内的最高价（high）和最低价（low），用于冷静期判断 */
    private double[] computeWindowHighLow(List<EthKlineSecond> allData, int left, int right) {
        double maxHigh = Double.MIN_VALUE;
        double minLow = Double.MAX_VALUE;
        for (int i = left; i <= right; i++) {
            double high = parseDouble(allData.get(i).getHigh());
            double low = parseDouble(allData.get(i).getLow());
            if (high > maxHigh) maxHigh = high;
            if (low < minLow) minLow = low;
        }
        return new double[]{maxHigh, minLow};
    }

    /**
     * 应用冷静期
     * 规则：向前扫描未来数据，找到首个完整1分钟无突破窗口
     *       - 空单：1分钟内没有出现比原极值更高的价格
     *       - 多单：1分钟内没有出现比原极值更低的价格
     * 最多等待15分钟，超时返回null（取消开单）
     *
     * @return long[]{延迟开单时间, 延迟开单价格} 或 null（超时取消）
     */
    private long[] applyCoolingPeriod(List<EthKlineSecond> allData, int startIdx,
                                       boolean isShort, double extremeHigh, double extremeLow,
                                       long currentTs) {
        double currentExtreme = isShort ? extremeHigh : extremeLow;
        long lastBreakMs = currentTs - 1000000L;
        long maxTime = currentTs + 15 * 60 * 1000000L;

        for (int i = startIdx; i < allData.size(); i++) {
            long t = allData.get(i).getTimestamp();
            if (t > maxTime) break;

            double high = parseDouble(allData.get(i).getHigh());
            double low = parseDouble(allData.get(i).getLow());
            double close = parseDouble(allData.get(i).getClose());
            boolean broken = (isShort && high > currentExtreme) || (!isShort && low < currentExtreme);

            if (broken) {
                lastBreakMs = t;
                currentExtreme = isShort ? high : low;
            }

            if (t - lastBreakMs >= 61 * 1000000L) {
                return new long[]{t, Double.doubleToLongBits(close)};
            }
        }

        return null;
    }

    /**
     * 创建含冷静期的交易记录
     * 先调用冷静期判断，通过后按延迟开单价格计算结算
     *
     * @return TradeRecord 或 null（冷静期未通过）
     */
    private TradeRecord createTradeRecordWithCooling(List<EthKlineSecond> allData,
                                                      int currentIdx, String direction,
                                                      double high20min, double low20min,
                                                      double windowMaxHigh, double windowMinLow,
                                                      String high20minTime, String low20minTime,
                                                      Map<Long, EthKlineSecond> dataMap, long currentTs) {
        boolean isShort = "空单".equals(direction);
        long[] result = applyCoolingPeriod(allData, currentIdx, isShort, windowMaxHigh, windowMinLow, currentTs);
        if (result == null) return null;

        long delayedOpenTs = result[0];
        double delayedOpenPrice = Double.longBitsToDouble(result[1]);
        int coolingSecs = (int) ((delayedOpenTs - currentTs) / 1000000L);

        TradeRecord record = new TradeRecord();
        record.setOpenTime(formatTimestamp(currentTs));
        record.setOpenTimestamp(currentTs);
        record.setDirection(direction);
        record.setOpenPrice(delayedOpenPrice);
        record.setHigh20min(high20min);
        record.setLow20min(low20min);
        record.setHigh20minTime(high20minTime);
        record.setLow20minTime(low20minTime);
        record.setDelayedOpenTimestamp(delayedOpenTs);
        record.setDelayedOpenPrice(delayedOpenPrice);
        record.setCoolingSeconds(coolingSecs);
        record.setCoolingPassed(true);
        record.setCoolingStatus("通过");

        long targetTs = delayedOpenTs + TEN_MINUTES_US;
        EthKlineSecond targetData = dataMap.get(targetTs);
        if (targetData != null) {
            record.setTenMinuteLaterPrice(parseDouble(targetData.getClose()));
            record.setTenMinuteLaterTime(formatTimestamp(targetTs));
            double profit = "多单".equals(direction)
                    ? record.getTenMinuteLaterPrice() - record.getOpenPrice()
                    : record.getOpenPrice() - record.getTenMinuteLaterPrice();
            record.setProfit(profit);
            record.setProfitPercent((profit / record.getOpenPrice()) * 100);
            record.setWin(profit > 0);
        }

        return record;
    }

    // ==================== ER 震荡/单边 判别策略 ====================

    /** ER 周期：5分钟 = 300条K线 */
    private static final int ER_LOOKBACK = 300;
    /** ER 震荡阈值 */
    private static final double ER_RANGING = 0.20;
    /** ER 单边阈值 */
    private static final double ER_TRENDING = 0.65;

    /**
     * ER 震荡市极值回归策略回测
     *
     * 逻辑：
     * 1. 计算 5 分钟效率比率（ER），判断震荡/单边
     * 2. 震荡市：检查过去 20 分钟窗口，当前价是否为极值
     *    - 当前价 = 窗口最低价 → 开多
     *    - 当前价 = 窗口最高价 → 开空
     * 3. 单边市：跳过，不交易
     * 4. 10 分钟后结算，统计盈亏
     */
    @Test
    public void testERStrategy() throws IOException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     ETH/USDT ER震荡市极值回归策略回测                         ║");
        System.out.println("║     5分钟ER判市 → 20分钟极值 → 10分钟结算                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        long t0 = System.currentTimeMillis();

        // ========== 1. 加载全量数据 ==========
        System.out.println("[1/4] 加载全量数据...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        if (sample.isEmpty()) { System.out.println("数据库无数据"); return; }
        boolean isMicro = sample.get(0).getTimestamp() > 1000000000000000L;

        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(0L, Long.MAX_VALUE);
        if (allData.isEmpty()) { System.out.println("数据库无数据"); return; }

        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        int N = allData.size();
        System.out.println("  加载完成: " + formatNumber(N) + " 条, 时间戳格式: " + (isMicro ? "微秒" : "毫秒"));

        // ========== 2. 预计算原始数组 ==========
        System.out.println("\n[2/4] 预计算原始数组...");
        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];

        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
        }

        // 预计算绝对价格变化数组（ER 滑动窗口用）
        double[] absChanges = new double[N];
        absChanges[0] = 0;
        for (int i = 1; i < N; i++) {
            absChanges[i] = Math.abs(closes[i] - closes[i - 1]);
        }

        allData = null;
        System.gc();
        System.out.println("  预计算完成: " + formatNumber(N) + " 条");

        // ========== 3. 回测主循环 ==========
        System.out.println("\n[3/4] 执行回测...");
        long twentyMinUs = 20 * 60 * (isMicro ? 1000000L : 1000L);
        long tenMinUs = 10 * 60 * (isMicro ? 1000000L : 1000L);
        int warmup = Math.max(ER_LOOKBACK, 20 * 60); // 取 5分钟 和 20分钟 中较大者

        // 单调队列（20分钟窗口极值）
        int[] maxDeque = new int[N];
        int[] minDeque = new int[N];
        int maxHead = 0, maxTail = 0, minHead = 0, minTail = 0;
        int left = 0;

        // ER 滑动窗口累加和
        double erSum = 0;

        // 统计
        int totalTrades = 0, wins = 0;
        int skippedTrending = 0, skippedNonExtreme = 0, skippedDuplicate = 0, skippedByHour = 0;
        int rangingTrades = 0, rangingWins = 0;
        int consecutiveLose = 0, maxConsecutiveLose = 0;
        double totalProfit = 0;

        List<TradeRecord> records = new ArrayList<>();

        // 去重：同一极值价只开一单
        double lastMaxPrice = -1;
        double lastMinPrice = -1;

        for (int right = 0; right < N; right++) {
            long currentTs = timestamps[right];
            double currentHigh = highs[right];
            double currentLow = lows[right];
            double currentClose = closes[right];

            // 单调队列维护
            while (maxHead < maxTail && highs[maxDeque[maxTail - 1]] <= currentHigh) maxTail--;
            maxDeque[maxTail++] = right;
            while (minHead < minTail && lows[minDeque[minTail - 1]] >= currentLow) minTail--;
            minDeque[minTail++] = right;

            // 滑动窗口左边界
            long windowStart = currentTs - twentyMinUs;
            while (left < right && timestamps[left] < windowStart) {
                if (maxHead < maxTail && maxDeque[maxHead] == left) maxHead++;
                if (minHead < minTail && minDeque[minHead] == left) minHead++;
                left++;
            }

            // 更新 ER 滑动窗口累加和
            if (right > 0) {
                erSum += absChanges[right];
                if (right > ER_LOOKBACK) {
                    erSum -= absChanges[right - ER_LOOKBACK];
                }
            }

            // 预热阶段跳过
            if (right < warmup) continue;

            // 计算 ER 并判断市场状态
            double er = -1;
            if (right >= ER_LOOKBACK && erSum > 0) {
                double netChange = Math.abs(closes[right] - closes[right - ER_LOOKBACK]);
                er = netChange / erSum;
            }
            boolean isRanging = er >= 0 && er < ER_RANGING;
            boolean isTrending = er > ER_TRENDING;

            // 单边市跳过
            if (isTrending) {
                skippedTrending++;
                continue;
            }

            // 震荡市：检查当前是否为极值
            if (!isRanging || right - left < 60 || maxHead >= maxTail || minHead >= minTail) {
                continue;
            }

            // 时段过滤：只在高胜率时段交易（北京时间: 00-01, 06-07, 12-13, 15, 19, 22-23）
            long epochSecond = timestamps[right] / (isMicro ? 1000000L : 1000L);
            int hour = java.time.Instant.ofEpochSecond(epochSecond)
                    .atZone(ZoneId.systemDefault()).getHour();
            boolean allowedHour = hour == 0 || hour == 1 || hour == 6 || hour == 7
                    || hour == 12 || hour == 13 || hour == 15
                    || hour == 19 || hour == 22 || hour == 23;
            if (!allowedHour) {
                skippedByHour++;
                continue;
            }

            int maxIdx = maxDeque[maxHead];
            int minIdx = minDeque[minHead];
            double windowMax = highs[maxIdx];
            double windowMin = lows[minIdx];

            boolean isHighExtreme = currentHigh >= windowMax;
            boolean isLowExtreme = currentLow <= windowMin;

            if (!isHighExtreme && !isLowExtreme) {
                skippedNonExtreme++;
                continue;
            }

            // 同一极值价只开一单
            if (isHighExtreme && Math.abs(windowMax - lastMaxPrice) < 1e-8) {
                skippedDuplicate++;
                continue;
            }
            if (isLowExtreme && Math.abs(windowMin - lastMinPrice) < 1e-8) {
                skippedDuplicate++;
                continue;
            }
            if (isHighExtreme) lastMaxPrice = windowMax;
            if (isLowExtreme) lastMinPrice = windowMin;

            // 查找 10 分钟后的结算价
            long settleTs = currentTs + tenMinUs;
            int settleIdx = java.util.Arrays.binarySearch(timestamps, right, Math.min(N - 1, right + 1200), settleTs);
            if (settleIdx < 0) settleIdx = -settleIdx - 1;
            if (settleIdx >= N) continue;
            double settlePrice = closes[settleIdx];

            TradeRecord rec = new TradeRecord();
            rec.setOpenTimestamp(currentTs);
            rec.setOpenTime(formatTimestamp(currentTs));
            rec.setOpenPrice(currentClose);
            rec.setHigh20min(windowMax);
            rec.setLow20min(windowMin);
            rec.setHigh20minTime(formatTimestamp(timestamps[maxIdx]));
            rec.setLow20minTime(formatTimestamp(timestamps[minIdx]));
            rec.setTenMinuteLaterPrice(settlePrice);
            rec.setTenMinuteLaterTime(formatTimestamp(settleTs));

            if (isHighExtreme) {
                // 开空：当前价是窗口最高价
                rec.setDirection("空单");
                rec.setTriggerExtreme("最高价 " + String.format("%.4f", windowMax));
                rec.setTriggerExtremeTime(formatTimestamp(timestamps[maxIdx]));
                double rawProfit = currentClose - settlePrice;
                boolean isWin = rawProfit > 0;
                rec.setProfit(isWin ? 4.0 : -5.0);
                rec.setProfitPercent((rawProfit / currentClose) * 100);
                rec.setWin(isWin);
            } else {
                // 开多：当前价是窗口最低价
                rec.setDirection("多单");
                rec.setTriggerExtreme("最低价 " + String.format("%.4f", windowMin));
                rec.setTriggerExtremeTime(formatTimestamp(timestamps[minIdx]));
                double rawProfit = settlePrice - currentClose;
                boolean isWin = rawProfit > 0;
                rec.setProfit(isWin ? 4.0 : -5.0);
                rec.setProfitPercent((rawProfit / currentClose) * 100);
                rec.setWin(isWin);
            }

            records.add(rec);
            totalTrades++;
            totalProfit += rec.getProfit();
            rangingTrades++;
            if (rec.isWin()) {
                wins++;
                rangingWins++;
                consecutiveLose = 0;
            } else {
                consecutiveLose++;
                if (consecutiveLose > maxConsecutiveLose) maxConsecutiveLose = consecutiveLose;
            }

            // 进度日志
            if (right % 100000 == 0) {
                System.out.println("  进度: " + formatNumber(right) + "/" + formatNumber(N)
                        + " (" + String.format("%.1f%%", right * 100.0 / N) + ")"
                        + " 交易:" + totalTrades + " 跳过:" + skippedTrending);
            }
        }

        long t1 = System.currentTimeMillis();
        double winRate = totalTrades > 0 ? wins * 100.0 / totalTrades : 0;
        double rangingWinRate = rangingTrades > 0 ? rangingWins * 100.0 / rangingTrades : 0;

        // ========== 4. 输出报告 ==========
        System.out.println("\n[4/4] 回测报告");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  数据总量:       " + formatNumber(N));
        System.out.println("  时间范围:       " + formatTimestamp(timestamps[0]) + " ~ " + formatTimestamp(timestamps[N - 1]));
        System.out.println("  ER周期:         5分钟 (" + ER_LOOKBACK + "条K线)");
        System.out.println("  震荡阈值:       ER < " + ER_RANGING);
        System.out.println("  单边阈值:       ER > " + ER_TRENDING);
        System.out.println("  极值窗口:       20分钟");
        System.out.println("  结算时间:       10分钟");
        System.out.println("───────────────────────────────────────────────────────────");
        System.out.println("  震荡市总交易:   " + totalTrades);
        System.out.println("  震荡市胜场:     " + rangingWins);
        System.out.println("  震荡市胜率:     " + String.format("%.2f%%", rangingWinRate));
        System.out.println("  单边市跳过:     " + skippedTrending);
        System.out.println("  非极值跳过:     " + skippedNonExtreme);
        System.out.println("  重复跳过:       " + skippedDuplicate);
        System.out.println("  时段跳过:       " + skippedByHour);
        System.out.println("  总盈亏:         " + String.format("%+.2f U", totalProfit));
        System.out.println("  最大连亏:       " + maxConsecutiveLose);
        System.out.println("───────────────────────────────────────────────────────────");
        System.out.println("  回测耗时:       " + formatTime(t1 - t0));

        // 保存到 Excel
        String outputPath = "D:\\trading_er_strategy.xlsx";
        saveToExcel(records, outputPath, "ER震荡策略");
        System.out.println("  结果已保存:     " + outputPath);
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    private void saveToExcel(List<TradeRecord> records, String path, String sheetName) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        SXSSFSheet sheet = workbook.createSheet(sheetName);
        sheet.trackAllColumnsForAutoSizing();
        String[] headers = {"开单时间", "方向", "开单价", "20分钟最高价", "最高价时间", "20分钟最低价", "最低价时间",
                "触发极值", "极值时间", "10分钟后价格", "10分钟后时间", "盈亏", "盈亏%", "结果"};

        // 表头样式
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        // 高亮表头样式（触发极值、极值时间）
        CellStyle highlightHeaderStyle = workbook.createCellStyle();
        highlightHeaderStyle.setFont(headerFont);
        highlightHeaderStyle.setFillForegroundColor(IndexedColors.GOLD.getIndex());
        highlightHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // 高亮数据样式
        CellStyle highlightDataStyle = workbook.createCellStyle();
        highlightDataStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        highlightDataStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(i == 7 || i == 8 ? highlightHeaderStyle : headerStyle);
        }

        int rowIdx = 1;
        for (TradeRecord r : records) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(r.getOpenTime());
            row.createCell(1).setCellValue(r.getDirection());
            row.createCell(2).setCellValue(r.getOpenPrice());
            row.createCell(3).setCellValue(r.getHigh20min());
            row.createCell(4).setCellValue(r.getHigh20minTime());
            row.createCell(5).setCellValue(r.getLow20min());
            row.createCell(6).setCellValue(r.getLow20minTime());

            Cell triggerCell = row.createCell(7);
            triggerCell.setCellValue(r.getTriggerExtreme() != null ? r.getTriggerExtreme() : "");
            triggerCell.setCellStyle(highlightDataStyle);

            Cell triggerTimeCell = row.createCell(8);
            triggerTimeCell.setCellValue(r.getTriggerExtremeTime() != null ? r.getTriggerExtremeTime() : "");
            triggerTimeCell.setCellStyle(highlightDataStyle);

            row.createCell(9).setCellValue(r.getTenMinuteLaterPrice());
            row.createCell(10).setCellValue(r.getTenMinuteLaterTime());
            row.createCell(11).setCellValue(r.getProfit());
            row.createCell(12).setCellValue(r.getProfitPercent());
            row.createCell(13).setCellValue(r.isWin() ? "胜利" : "亏损");
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        try (FileOutputStream fos = new FileOutputStream(path)) {
            workbook.write(fos);
        }
        workbook.close();
        workbook.dispose();
    }

    /**
     * 极值窗口网格搜索：遍历不同窗口大小，找出最优极值时间范围
     * 固定参数：ER 5分钟判市(震荡<0.20)，时段过滤，10分钟结算，+4U/-5U
     * 变量：极值窗口 5/10/15/20/25/30/40/50/60/90/120 分钟
     */
    @Test
    public void testExtremeWindowGrid() throws IOException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     极值窗口网格搜索 — 找最优极值时间范围                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        long t0 = System.currentTimeMillis();

        // ========== 1. 加载全量数据 ==========
        System.out.println("[1/3] 加载全量数据...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        if (sample.isEmpty()) { System.out.println("数据库无数据"); return; }
        boolean isMicro = sample.get(0).getTimestamp() > 1000000000000000L;

        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(0L, Long.MAX_VALUE);
        if (allData.isEmpty()) { System.out.println("数据库无数据"); return; }

        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        int N = allData.size();
        System.out.println("  加载完成: " + formatNumber(N) + " 条, 时间戳格式: " + (isMicro ? "微秒" : "毫秒"));

        // 预计算原始数组
        System.out.println("  预计算原始数组...");
        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];

        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
        }

        double[] absChanges = new double[N];
        absChanges[0] = 0;
        for (int i = 1; i < N; i++) {
            absChanges[i] = Math.abs(closes[i] - closes[i - 1]);
        }

        allData = null;
        System.gc();

        long tenMinUs = 10 * 60 * (isMicro ? 1000000L : 1000L);

        // 极值窗口候选（分钟）
        int[] windowMinutes = {5, 10, 15, 20, 25, 30, 40, 50, 60, 90, 120};

        // 结果收集
        List<String[]> gridResults = new ArrayList<>();
        gridResults.add(new String[]{"窗口(分钟)", "交易数", "胜利", "亏损", "胜率%", "空单数", "空胜率%",
                "多单数", "多胜率%", "总盈亏U", "最大连亏", "单边跳过", "时段跳过", "重复跳过", "耗时"});

        System.out.println("\n[2/3] 开始网格搜索 (" + windowMinutes.length + " 个窗口)...");

        for (int wi = 0; wi < windowMinutes.length; wi++) {
            int winMin = windowMinutes[wi];
            long wStart = System.currentTimeMillis();

            long windowUs = winMin * 60L * (isMicro ? 1000000L : 1000L);
            int warmup = Math.max(ER_LOOKBACK, winMin * 60);

            // 单调队列
            int[] maxDeque = new int[N];
            int[] minDeque = new int[N];
            int maxHead = 0, maxTail = 0, minHead = 0, minTail = 0;
            int left = 0;

            double erSum = 0;

            int totalTrades = 0, wins = 0;
            int skippedTrending = 0, skippedDuplicate = 0, skippedByHour = 0;
            int shortTrades = 0, shortWins = 0;
            int longTrades = 0, longWins = 0;
            int consecutiveLose = 0, maxConsecutiveLose = 0;
            double totalProfit = 0;

            double lastMaxPrice = -1;
            double lastMinPrice = -1;

            for (int right = 0; right < N; right++) {
                long currentTs = timestamps[right];
                double currentHigh = highs[right];
                double currentLow = lows[right];
                double currentClose = closes[right];

                // 单调队列维护
                while (maxHead < maxTail && highs[maxDeque[maxTail - 1]] <= currentHigh) maxTail--;
                maxDeque[maxTail++] = right;
                while (minHead < minTail && lows[minDeque[minTail - 1]] >= currentLow) minTail--;
                minDeque[minTail++] = right;

                // 滑动窗口左边界
                long windowStart = currentTs - windowUs;
                while (left < right && timestamps[left] < windowStart) {
                    if (maxHead < maxTail && maxDeque[maxHead] == left) maxHead++;
                    if (minHead < minTail && minDeque[minHead] == left) minHead++;
                    left++;
                }

                // ER 滑动窗口
                if (right > 0) {
                    erSum += absChanges[right];
                    if (right > ER_LOOKBACK) {
                        erSum -= absChanges[right - ER_LOOKBACK];
                    }
                }

                if (right < warmup) continue;

                // ER 判市
                double er = -1;
                if (right >= ER_LOOKBACK && erSum > 0) {
                    double netChange = Math.abs(closes[right] - closes[right - ER_LOOKBACK]);
                    er = netChange / erSum;
                }
                boolean isRanging = er >= 0 && er < ER_RANGING;
                boolean isTrending = er > ER_TRENDING;

                if (isTrending) { skippedTrending++; continue; }
                if (!isRanging || right - left < 60 || maxHead >= maxTail || minHead >= minTail) continue;

                // 时段过滤
                long epochSecond = timestamps[right] / (isMicro ? 1000000L : 1000L);
                int hour = java.time.Instant.ofEpochSecond(epochSecond)
                        .atZone(ZoneId.systemDefault()).getHour();
                boolean allowedHour = hour == 0 || hour == 1 || hour == 6 || hour == 7
                        || hour == 12 || hour == 13 || hour == 15
                        || hour == 19 || hour == 22 || hour == 23;
                if (!allowedHour) { skippedByHour++; continue; }

                int maxIdx = maxDeque[maxHead];
                int minIdx = minDeque[minHead];
                double windowMax = highs[maxIdx];
                double windowMin = lows[minIdx];

                boolean isHighExtreme = currentHigh >= windowMax;
                boolean isLowExtreme = currentLow <= windowMin;

                if (!isHighExtreme && !isLowExtreme) continue;

                // 去重
                if (isHighExtreme && Math.abs(windowMax - lastMaxPrice) < 1e-8) {
                    skippedDuplicate++; continue;
                }
                if (isLowExtreme && Math.abs(windowMin - lastMinPrice) < 1e-8) {
                    skippedDuplicate++; continue;
                }
                if (isHighExtreme) lastMaxPrice = windowMax;
                if (isLowExtreme) lastMinPrice = windowMin;

                // 结算
                long settleTs = currentTs + tenMinUs;
                int settleIdx = java.util.Arrays.binarySearch(timestamps, right, Math.min(N - 1, right + 1200), settleTs);
                if (settleIdx < 0) settleIdx = -settleIdx - 1;
                if (settleIdx >= N) continue;
                double settlePrice = closes[settleIdx];

                boolean isWin;
                if (isHighExtreme) {
                    isWin = currentClose > settlePrice;
                    shortTrades++;
                    if (isWin) shortWins++;
                } else {
                    isWin = settlePrice > currentClose;
                    longTrades++;
                    if (isWin) longWins++;
                }

                totalTrades++;
                totalProfit += isWin ? 4.0 : -5.0;
                if (isWin) {
                    wins++;
                    consecutiveLose = 0;
                } else {
                    consecutiveLose++;
                    if (consecutiveLose > maxConsecutiveLose) maxConsecutiveLose = consecutiveLose;
                }
            }

            long wElapsed = System.currentTimeMillis() - wStart;
            double winRate = totalTrades > 0 ? wins * 100.0 / totalTrades : 0;
            double shortWinRate = shortTrades > 0 ? shortWins * 100.0 / shortTrades : 0;
            double longWinRate = longTrades > 0 ? longWins * 100.0 / longTrades : 0;

            gridResults.add(new String[]{
                    String.valueOf(winMin),
                    String.valueOf(totalTrades),
                    String.valueOf(wins),
                    String.valueOf(totalTrades - wins),
                    String.format("%.2f", winRate),
                    String.valueOf(shortTrades),
                    String.format("%.2f", shortWinRate),
                    String.valueOf(longTrades),
                    String.format("%.2f", longWinRate),
                    String.format("%+.2f", totalProfit),
                    String.valueOf(maxConsecutiveLose),
                    String.valueOf(skippedTrending),
                    String.valueOf(skippedByHour),
                    String.valueOf(skippedDuplicate),
                    formatTime(wElapsed)
            });

            System.out.println(String.format("  [%d/%d] %d分钟窗口 | 交易:%d | 胜率:%.2f%% | 盈亏:%+.1fU | 耗时:%s",
                    wi + 1, windowMinutes.length, winMin, totalTrades, winRate, totalProfit, formatTime(wElapsed)));
        }

        // ========== 3. 输出 CSV ==========
        long t1 = System.currentTimeMillis();
        String csvPath = "D:\\extreme_window_grid.csv";
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(csvPath), "UTF-8"))) {
            for (String[] row : gridResults) {
                pw.println(String.join(",", row));
            }
        }

        System.out.println("\n[3/3] 网格搜索完成");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  总耗时:         " + formatTime(t1 - t0));
        System.out.println("  结果已保存:     " + csvPath);
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    /**
     * 事件合约全网格搜索：极值窗口 × 结算时间 = 找最优方案
     *
     * 结算规则（事件合约）：
     *   10分钟: 胜利得开单金额的80%, 失败全亏(-100%)
     *   30分钟/1小时/1天: 胜利得85%, 失败全亏(-100%)
     *
     * 网格维度:
     *   X轴: 极值窗口 5/10/15/20/25/30/40/50/60/90/120 分钟
     *   Y轴: 结算时间 10分钟/30分钟/1小时/1天
     *
     * 固定参数: ER 5分钟判市(震荡<0.20), 时段过滤, 同一极值只开一单
     */
    @Test
    public void testFullGridSearch() throws IOException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     事件合约全网格搜索 — 极值窗口 × 结算时间                    ║");
        System.out.println("║     10分钟(80%) / 30分钟/1小时/1天(85%)                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        long t0 = System.currentTimeMillis();

        // ========== 1. 加载全量数据 ==========
        System.out.println("[1/3] 加载全量数据...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        if (sample.isEmpty()) { System.out.println("数据库无数据"); return; }
        boolean isMicro = sample.get(0).getTimestamp() > 1000000000000000L;

        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(0L, Long.MAX_VALUE);
        if (allData.isEmpty()) { System.out.println("数据库无数据"); return; }

        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        int N = allData.size();
        System.out.println("  加载完成: " + formatNumber(N) + " 条, 时间戳格式: " + (isMicro ? "微秒" : "毫秒"));

        // 预计算原始数组
        System.out.println("  预计算原始数组...");
        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];

        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
        }

        double[] absChanges = new double[N];
        absChanges[0] = 0;
        for (int i = 1; i < N; i++) {
            absChanges[i] = Math.abs(closes[i] - closes[i - 1]);
        }

        allData = null;
        System.gc();

        // 极值窗口候选（分钟）
        int[] windowMinutes = {5, 10, 15, 20, 25, 30, 40, 50, 60, 90, 120};

        // 结算时间定义: {名称, 分钟数, 胜率收益}
        String[][] settleDefs = {
                {"10分钟", "10", "0.80"},
                {"30分钟", "30", "0.85"},
                {"1小时", "60", "0.85"},
                {"1天", "1440", "0.85"},
        };

        // 结果收集
        List<String[]> gridResults = new ArrayList<>();
        gridResults.add(new String[]{"极值窗口(分)", "结算时间", "胜率收益", "交易数", "胜利", "亏损",
                "胜率%", "空单数", "空胜率%", "多单数", "多胜率%", "总盈亏(U)", "盈亏平衡胜率%",
                "最大连亏", "单边跳过", "时段跳过", "重复跳过", "耗时"});

        int totalCombos = windowMinutes.length * settleDefs.length;
        System.out.println("\n[2/3] 开始全网格搜索 (" + totalCombos + " 种组合)...");

        int comboIdx = 0;
        for (String[] settleDef : settleDefs) {
            String settleName = settleDef[0];
            int settleMinutes = Integer.parseInt(settleDef[1]);
            double winReturn = Double.parseDouble(settleDef[2]);
            long settleUs = settleMinutes * 60L * (isMicro ? 1000000L : 1000L);
            double beRate = 1.0 / (1.0 + winReturn) * 100; // 盈亏平衡胜率

            for (int winMin : windowMinutes) {
                comboIdx++;
                long wStart = System.currentTimeMillis();

                long windowUs = winMin * 60L * (isMicro ? 1000000L : 1000L);
                int warmup = Math.max(ER_LOOKBACK, Math.max(winMin * 60, settleMinutes * 60));

                int[] maxDeque = new int[N];
                int[] minDeque = new int[N];
                int maxHead = 0, maxTail = 0, minHead = 0, minTail = 0;
                int left = 0;

                double erSum = 0;

                int totalTrades = 0, wins = 0;
                int skippedTrending = 0, skippedDuplicate = 0, skippedByHour = 0;
                int shortTrades = 0, shortWins = 0;
                int longTrades = 0, longWins = 0;
                int consecutiveLose = 0, maxConsecutiveLose = 0;
                double totalProfit = 0;

                double lastMaxPrice = -1;
                double lastMinPrice = -1;

                // 结算搜索范围: 1天需要更大的搜索范围
                int settleSearchRange = Math.min(N - 1, settleMinutes * 60 + 1200);

                for (int right = 0; right < N; right++) {
                    long currentTs = timestamps[right];
                    double currentHigh = highs[right];
                    double currentLow = lows[right];
                    double currentClose = closes[right];

                    while (maxHead < maxTail && highs[maxDeque[maxTail - 1]] <= currentHigh) maxTail--;
                    maxDeque[maxTail++] = right;
                    while (minHead < minTail && lows[minDeque[minTail - 1]] >= currentLow) minTail--;
                    minDeque[minTail++] = right;

                    long windowStart = currentTs - windowUs;
                    while (left < right && timestamps[left] < windowStart) {
                        if (maxHead < maxTail && maxDeque[maxHead] == left) maxHead++;
                        if (minHead < minTail && minDeque[minHead] == left) minHead++;
                        left++;
                    }

                    if (right > 0) {
                        erSum += absChanges[right];
                        if (right > ER_LOOKBACK) {
                            erSum -= absChanges[right - ER_LOOKBACK];
                        }
                    }

                    if (right < warmup) continue;

                    double er = -1;
                    if (right >= ER_LOOKBACK && erSum > 0) {
                        double netChange = Math.abs(closes[right] - closes[right - ER_LOOKBACK]);
                        er = netChange / erSum;
                    }
                    boolean isRanging = er >= 0 && er < ER_RANGING;
                    boolean isTrending = er > ER_TRENDING;

                    if (isTrending) { skippedTrending++; continue; }
                    if (!isRanging || right - left < 60 || maxHead >= maxTail || minHead >= minTail) continue;

                    long epochSecond = timestamps[right] / (isMicro ? 1000000L : 1000L);
                    int hour = java.time.Instant.ofEpochSecond(epochSecond)
                            .atZone(ZoneId.systemDefault()).getHour();
                    boolean allowedHour = hour == 0 || hour == 1 || hour == 6 || hour == 7
                            || hour == 12 || hour == 13 || hour == 15
                            || hour == 19 || hour == 22 || hour == 23;
                    if (!allowedHour) { skippedByHour++; continue; }

                    int maxIdx = maxDeque[maxHead];
                    int minIdx = minDeque[minHead];
                    double windowMax = highs[maxIdx];
                    double windowMin = lows[minIdx];

                    boolean isHighExtreme = currentHigh >= windowMax;
                    boolean isLowExtreme = currentLow <= windowMin;

                    if (!isHighExtreme && !isLowExtreme) continue;

                    if (isHighExtreme && Math.abs(windowMax - lastMaxPrice) < 1e-8) {
                        skippedDuplicate++; continue;
                    }
                    if (isLowExtreme && Math.abs(windowMin - lastMinPrice) < 1e-8) {
                        skippedDuplicate++; continue;
                    }
                    if (isHighExtreme) lastMaxPrice = windowMax;
                    if (isLowExtreme) lastMinPrice = windowMin;

                    long settleTs = currentTs + settleUs;
                    int settleIdx = java.util.Arrays.binarySearch(
                            timestamps, right, Math.min(N - 1, right + settleSearchRange), settleTs);
                    if (settleIdx < 0) settleIdx = -settleIdx - 1;
                    if (settleIdx >= N) continue;
                    double settlePrice = closes[settleIdx];

                    boolean isWin;
                    if (isHighExtreme) {
                        isWin = currentClose > settlePrice;
                        shortTrades++;
                        if (isWin) shortWins++;
                    } else {
                        isWin = settlePrice > currentClose;
                        longTrades++;
                        if (isWin) longWins++;
                    }

                    totalTrades++;
                    totalProfit += isWin ? winReturn : -1.0;
                    if (isWin) {
                        wins++;
                        consecutiveLose = 0;
                    } else {
                        consecutiveLose++;
                        if (consecutiveLose > maxConsecutiveLose) maxConsecutiveLose = consecutiveLose;
                    }
                }

                long wElapsed = System.currentTimeMillis() - wStart;
                double winRate = totalTrades > 0 ? wins * 100.0 / totalTrades : 0;
                double shortWinRate = shortTrades > 0 ? shortWins * 100.0 / shortTrades : 0;
                double longWinRate = longTrades > 0 ? longWins * 100.0 / longTrades : 0;

                gridResults.add(new String[]{
                        String.valueOf(winMin),
                        settleName,
                        String.format("%.0f%%", winReturn * 100),
                        String.valueOf(totalTrades),
                        String.valueOf(wins),
                        String.valueOf(totalTrades - wins),
                        String.format("%.2f", winRate),
                        String.valueOf(shortTrades),
                        String.format("%.2f", shortWinRate),
                        String.valueOf(longTrades),
                        String.format("%.2f", longWinRate),
                        String.format("%+.2f", totalProfit),
                        String.format("%.2f", beRate),
                        String.valueOf(maxConsecutiveLose),
                        String.valueOf(skippedTrending),
                        String.valueOf(skippedByHour),
                        String.valueOf(skippedDuplicate),
                        formatTime(wElapsed)
                });

                System.out.println(String.format("  [%d/%d] %s结算 | %d分极值窗口 | 交易:%d | 胜率:%.2f%% | 盈亏:%+.1fU | 耗时:%s",
                        comboIdx, totalCombos, settleName, winMin, totalTrades, winRate, totalProfit, formatTime(wElapsed)));
            }
        }

        // ========== 3. 输出 CSV ==========
        long t1 = System.currentTimeMillis();
        String csvPath = "D:\\full_grid_search.csv";
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(csvPath), "UTF-8"))) {
            for (String[] row : gridResults) {
                pw.println(String.join(",", row));
            }
        }

        System.out.println("\n[3/3] 全网格搜索完成");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  总组合数:       " + totalCombos);
        System.out.println("  总耗时:         " + formatTime(t1 - t0));
        System.out.println("  结果已保存:     " + csvPath);
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    /**
     * 推荐三方案详细回测，输出精确的开单时间/判定条件/结果
     *
     * 方案A: 10分钟结算 + 25分钟极值 + 胜+80% 亏-100%
     * 方案B: 30分钟结算 + 40分钟极值 + 胜+85% 亏-100%
     * 方案C: 1小时结算 + 40分钟极值 + 胜+85% 亏-100%
     *
     * 固定: ER 5分钟判市(震荡<0.20), 时段过滤, 同一极值只开一单
     */
    @Test
    public void testRecommendedStrategies() throws IOException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     推荐三方案详细回测                                       ║");
        System.out.println("║     A: 10分结算+25分极值(80%)                                ║");
        System.out.println("║     B: 30分结算+40分极值(85%)                                ║");
        System.out.println("║     C: 1H结算+40分极值(85%)                                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        long t0 = System.currentTimeMillis();

        // ========== 1. 加载全量数据 ==========
        System.out.println("[1/3] 加载全量数据...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        if (sample.isEmpty()) { System.out.println("数据库无数据"); return; }
        boolean isMicro = sample.get(0).getTimestamp() > 1000000000000000L;

        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(0L, Long.MAX_VALUE);
        if (allData.isEmpty()) { System.out.println("数据库无数据"); return; }

        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        int N = allData.size();
        System.out.println("  加载完成: " + formatNumber(N) + " 条");

        // 预计算原始数组
        System.out.println("  预计算原始数组...");
        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];

        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
        }

        double[] absChanges = new double[N];
        absChanges[0] = 0;
        for (int i = 1; i < N; i++) {
            absChanges[i] = Math.abs(closes[i] - closes[i - 1]);
        }

        allData = null;
        System.gc();

        // 三种方案定义: {名称, 极值窗口(分), 结算分钟, 胜利收益}
        String[][] plans = {
                {"方案A_10分结算_25分极值", "25", "10", "0.80"},
                {"方案B_30分结算_40分极值", "40", "30", "0.85"},
                {"方案C_1小时结算_40分极值", "40", "60", "0.85"},
        };

        System.out.println("\n[2/3] 执行三方案回测...");

        for (String[] plan : plans) {
            String planName = plan[0];
            int winMin = Integer.parseInt(plan[1]);
            int settleMinutes = Integer.parseInt(plan[2]);
            double winReturn = Double.parseDouble(plan[3]);
            long settleUs = settleMinutes * 60L * (isMicro ? 1000000L : 1000L);
            long windowUs = winMin * 60L * (isMicro ? 1000000L : 1000L);
            int warmup = Math.max(ER_LOOKBACK, Math.max(winMin * 60, settleMinutes * 60));
            int settleSearchRange = Math.min(N - 1, settleMinutes * 60 + 1200);

            // 单调队列
            int[] maxDeque = new int[N];
            int[] minDeque = new int[N];
            int maxHead = 0, maxTail = 0, minHead = 0, minTail = 0;
            int left = 0;
            double erSum = 0;

            int totalTrades = 0, wins = 0;
            int skippedTrending = 0, skippedDuplicate = 0, skippedByHour = 0;
            int consecutiveLose = 0, maxConsecutiveLose = 0;
            double totalProfit = 0;
            double lastMaxPrice = -1, lastMinPrice = -1;

            List<TradeRecord> records = new ArrayList<>();

            System.out.println("  " + planName + " ...");

            for (int right = 0; right < N; right++) {
                long currentTs = timestamps[right];
                double currentHigh = highs[right];
                double currentLow = lows[right];
                double currentClose = closes[right];

                while (maxHead < maxTail && highs[maxDeque[maxTail - 1]] <= currentHigh) maxTail--;
                maxDeque[maxTail++] = right;
                while (minHead < minTail && lows[minDeque[minTail - 1]] >= currentLow) minTail--;
                minDeque[minTail++] = right;

                long windowStart = currentTs - windowUs;
                while (left < right && timestamps[left] < windowStart) {
                    if (maxHead < maxTail && maxDeque[maxHead] == left) maxHead++;
                    if (minHead < minTail && minDeque[minHead] == left) minHead++;
                    left++;
                }

                if (right > 0) {
                    erSum += absChanges[right];
                    if (right > ER_LOOKBACK) erSum -= absChanges[right - ER_LOOKBACK];
                }

                if (right < warmup) continue;

                double er = -1;
                if (right >= ER_LOOKBACK && erSum > 0) {
                    double netChange = Math.abs(closes[right] - closes[right - ER_LOOKBACK]);
                    er = netChange / erSum;
                }
                boolean isRanging = er >= 0 && er < ER_RANGING;
                boolean isTrending = er > ER_TRENDING;

                if (isTrending) { skippedTrending++; continue; }
                if (!isRanging || right - left < 60 || maxHead >= maxTail || minHead >= minTail) continue;

                long epochSecond = timestamps[right] / (isMicro ? 1000000L : 1000L);
                int hour = java.time.Instant.ofEpochSecond(epochSecond)
                        .atZone(ZoneId.systemDefault()).getHour();
                boolean allowedHour = hour == 0 || hour == 1 || hour == 6 || hour == 7
                        || hour == 12 || hour == 13 || hour == 15
                        || hour == 19 || hour == 22 || hour == 23;
                if (!allowedHour) { skippedByHour++; continue; }

                int maxIdx = maxDeque[maxHead];
                int minIdx = minDeque[minHead];
                double windowMax = highs[maxIdx];
                double windowMin = lows[minIdx];

                boolean isHighExtreme = currentHigh >= windowMax;
                boolean isLowExtreme = currentLow <= windowMin;
                if (!isHighExtreme && !isLowExtreme) continue;

                if (isHighExtreme && Math.abs(windowMax - lastMaxPrice) < 1e-8) {
                    skippedDuplicate++; continue;
                }
                if (isLowExtreme && Math.abs(windowMin - lastMinPrice) < 1e-8) {
                    skippedDuplicate++; continue;
                }
                if (isHighExtreme) lastMaxPrice = windowMax;
                if (isLowExtreme) lastMinPrice = windowMin;

                long settleTs = currentTs + settleUs;
                int settleIdx = java.util.Arrays.binarySearch(
                        timestamps, right, Math.min(N - 1, right + settleSearchRange), settleTs);
                if (settleIdx < 0) settleIdx = -settleIdx - 1;
                if (settleIdx >= N) continue;
                double settlePrice = closes[settleIdx];

                TradeRecord rec = new TradeRecord();
                rec.setOpenTimestamp(currentTs);
                rec.setOpenTime(formatTimestamp(currentTs));
                rec.setOpenPrice(currentClose);
                rec.setHigh20min(windowMax);
                rec.setHigh20minTime(formatTimestamp(timestamps[maxIdx]));
                rec.setLow20min(windowMin);
                rec.setLow20minTime(formatTimestamp(timestamps[minIdx]));
                rec.setTenMinuteLaterPrice(settlePrice);
                rec.setTenMinuteLaterTime(formatTimestamp(settleTs));

                boolean isWin;
                double rawProfit;
                if (isHighExtreme) {
                    rec.setDirection("空单");
                    rec.setTriggerExtreme("最高价 " + String.format("%.4f", windowMax));
                    rec.setTriggerExtremeTime(formatTimestamp(timestamps[maxIdx]));
                    rawProfit = currentClose - settlePrice;
                    isWin = rawProfit > 0;
                } else {
                    rec.setDirection("多单");
                    rec.setTriggerExtreme("最低价 " + String.format("%.4f", windowMin));
                    rec.setTriggerExtremeTime(formatTimestamp(timestamps[minIdx]));
                    rawProfit = settlePrice - currentClose;
                    isWin = rawProfit > 0;
                }

                rec.setProfit(isWin ? winReturn : -1.0);
                rec.setProfitPercent((rawProfit / currentClose) * 100);
                rec.setWin(isWin);

                records.add(rec);
                totalTrades++;
                totalProfit += rec.getProfit();
                if (isWin) {
                    wins++;
                    consecutiveLose = 0;
                } else {
                    consecutiveLose++;
                    if (consecutiveLose > maxConsecutiveLose) maxConsecutiveLose = consecutiveLose;
                }
            }

            double winRate = totalTrades > 0 ? wins * 100.0 / totalTrades : 0;
            double beRate = 1.0 / (1.0 + winReturn) * 100;

            System.out.println("    交易:" + totalTrades + " 胜率:" + String.format("%.2f%%", winRate)
                    + " 盈亏:" + String.format("%+.2fU", totalProfit)
                    + " 最大连亏:" + maxConsecutiveLose
                    + " 单边跳过:" + skippedTrending
                    + " 时段跳过:" + skippedByHour
                    + " 重复跳过:" + skippedDuplicate);

            // 保存到 Excel
            String outputPath = "D:\\" + planName + ".xlsx";
            saveToExcelWithSettle(records, outputPath, planName, settleMinutes, winReturn, beRate,
                    totalTrades, wins, winRate, totalProfit, maxConsecutiveLose);
            System.out.println("    → 已保存: " + outputPath);
        }

        long t1 = System.currentTimeMillis();
        System.out.println("\n[3/3] 三方案回测完成");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  总耗时:         " + formatTime(t1 - t0));
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    /**
     * 带结算时间信息的Excel导出
     */
    private void saveToExcelWithSettle(List<TradeRecord> records, String path, String sheetName,
            int settleMinutes, double winReturn, double beRate,
            int totalTrades, int wins, double winRate, double totalProfit, int maxConsecutiveLose)
            throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        SXSSFSheet sheet = workbook.createSheet(sheetName);
        sheet.trackAllColumnsForAutoSizing();

        String[] headers = {"开单时间", "方向", "开单价", "极值窗口最高价", "最高价出现时间",
                "极值窗口最低价", "最低价出现时间", "触发极值", "极值出现时间",
                "结算时间", "结算价", "价格变动%", "盈亏U", "结果"};

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        CellStyle winStyle = workbook.createCellStyle();
        winStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        winStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle loseStyle = workbook.createCellStyle();
        loseStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        loseStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // 表头行
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;
        for (TradeRecord r : records) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(r.getOpenTime());
            row.createCell(1).setCellValue(r.getDirection());
            row.createCell(2).setCellValue(r.getOpenPrice());
            row.createCell(3).setCellValue(r.getHigh20min());
            row.createCell(4).setCellValue(r.getHigh20minTime() != null ? r.getHigh20minTime() : "");
            row.createCell(5).setCellValue(r.getLow20min());
            row.createCell(6).setCellValue(r.getLow20minTime() != null ? r.getLow20minTime() : "");
            row.createCell(7).setCellValue(r.getTriggerExtreme() != null ? r.getTriggerExtreme() : "");
            row.createCell(8).setCellValue(r.getTriggerExtremeTime() != null ? r.getTriggerExtremeTime() : "");
            row.createCell(9).setCellValue(r.getTenMinuteLaterTime() != null ? r.getTenMinuteLaterTime() : "");
            row.createCell(10).setCellValue(r.getTenMinuteLaterPrice());
            row.createCell(11).setCellValue(r.getProfitPercent());
            row.createCell(12).setCellValue(r.getProfit());
            row.createCell(13).setCellValue(r.isWin() ? "胜利" : "亏损");

            // 颜色标注
            CellStyle style = r.isWin() ? winStyle : loseStyle;
            for (int c = 0; c < headers.length; c++) {
                row.getCell(c).setCellStyle(style);
            }
        }

        // 汇总信息行（隔3行）
        int summaryRow = rowIdx + 3;
        CellStyle summaryStyle = workbook.createCellStyle();
        Font summaryFont = workbook.createFont();
        summaryFont.setBold(true);
        summaryStyle.setFont(summaryFont);

        Row r1 = sheet.createRow(summaryRow);
        createCell(r1, 0, "═══ 回测参数 ═══", summaryStyle);
        Row r2 = sheet.createRow(summaryRow + 1);
        createCell(r2, 0, "极值窗口: " + (records.isEmpty() ? "-" : "见文件名") + "分钟", summaryStyle);
        createCell(r2, 1, "结算时间: " + settleMinutes + "分钟", summaryStyle);
        createCell(r2, 2, "胜利收益: +" + String.format("%.0f%%", winReturn * 100), summaryStyle);
        createCell(r2, 3, "失败亏损: -100%", summaryStyle);
        createCell(r2, 4, "盈亏平衡胜率: " + String.format("%.2f%%", beRate), summaryStyle);

        Row r3 = sheet.createRow(summaryRow + 3);
        createCell(r3, 0, "═══ 回测结果 ═══", summaryStyle);
        Row r4 = sheet.createRow(summaryRow + 4);
        createCell(r4, 0, "总交易: " + totalTrades, summaryStyle);
        createCell(r4, 1, "胜利: " + wins, summaryStyle);
        createCell(r4, 2, "亏损: " + (totalTrades - wins), summaryStyle);
        createCell(r4, 3, "胜率: " + String.format("%.2f%%", winRate), summaryStyle);
        createCell(r4, 4, "总盈亏: " + String.format("%+.2fU", totalProfit), summaryStyle);
        createCell(r4, 5, "最大连亏: " + maxConsecutiveLose, summaryStyle);

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        try (FileOutputStream fos = new FileOutputStream(path)) {
            workbook.write(fos);
        }
        workbook.close();
        workbook.dispose();
    }

    @Test
    public void testBounceConfirmStrategy() throws IOException {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║        回踩确认策略 — 回测                               ║");
        System.out.println("║  核心逻辑: 极值出现后，等价格反弹0.01%~0.10%再开单       ║");
        System.out.println("║  结算规则: 10分→+4/-5, 30分/1H/1D→+4.25/-5               ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        long t0 = System.currentTimeMillis();

        System.out.println("[1/3] 加载全量数据...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        if (sample.isEmpty()) { System.out.println("数据库无数据"); return; }
        boolean isMicro = sample.get(0).getTimestamp() > 1000000000000000L;

        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(0L, Long.MAX_VALUE);
        if (allData.isEmpty()) { System.out.println("数据库无数据"); return; }

        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        int N = allData.size();
        System.out.println("  加载完成: " + formatNumber(N) + " 条");

        System.out.println("  预计算原始数组...");
        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];

        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
        }

        double[] absChanges = new double[N];
        absChanges[0] = 0;
        for (int i = 1; i < N; i++) {
            absChanges[i] = Math.abs(closes[i] - closes[i - 1]);
        }

        allData = null;
        System.gc();

        double[] bounceThresholds = {0.0001, 0.0002, 0.0003, 0.0005, 0.0007, 0.0010};
        int bounceWindowSec = 120;

        String[][] plans = {
                {"方案A_10分结算_25分极值", "25", "10", "4.0", "-5.0"},
                {"方案B_30分结算_40分极值", "40", "30", "4.25", "-5.0"},
                {"方案C_1H结算_40分极值",   "40", "60", "4.25", "-5.0"},
        };

        System.out.println("\n[2/3] 网格搜索回踩确认...");
        System.out.println("  回踩阈值: " + java.util.Arrays.toString(bounceThresholds));
        System.out.println("  确认窗口: " + bounceWindowSec + "秒");
        System.out.println();

        StringBuilder summaryCsv = new StringBuilder();
        summaryCsv.append("方案,回踩阈值%,结算分钟,极值窗口,交易数,胜率%,总盈亏U,单笔盈亏U,跳过无回踩,跳过突破,跳过超时,跳过时段,跳过重复,跳过趋势,最大连亏\n");

        for (String[] plan : plans) {
            String planName = plan[0];
            int winMin = Integer.parseInt(plan[1]);
            int settleMinutes = Integer.parseInt(plan[2]);
            double winReturn = Double.parseDouble(plan[3]);
            double loseReturn = Double.parseDouble(plan[4]);
            long settleUs = settleMinutes * 60L * (isMicro ? 1000000L : 1000L);
            long windowUs = winMin * 60L * (isMicro ? 1000000L : 1000L);
            int warmup = Math.max(ER_LOOKBACK, Math.max(winMin * 60, settleMinutes * 60));
            int settleSearchRange = Math.min(N - 1, settleMinutes * 60 + 1200);
            long bounceWindowUs = bounceWindowSec * (isMicro ? 1000000L : 1000L);

            System.out.println("  ====== " + planName + " (胜+" + winReturn + " / 亏" + loseReturn + ") ======");
            System.out.printf("  %-10s %8s %8s %8s %10s %10s %8s %8s %8s\n",
                    "回踩阈值", "交易数", "胜率%", "总盈亏", "单笔盈亏", "无回踩", "突破取消", "超时", "最大连亏");

            for (double bounceThreshold : bounceThresholds) {
                int[] maxDeque = new int[N];
                int[] minDeque = new int[N];
                int maxHead = 0, maxTail = 0, minHead = 0, minTail = 0;
                int left = 0;
                double erSum = 0;

                int totalTrades = 0, wins = 0;
                int skippedTrending = 0, skippedDuplicate = 0, skippedByHour = 0;
                int skippedNoBounce = 0, skippedBreakout = 0, skippedTimeout = 0;
                int consecutiveLose = 0, maxConsecutiveLose = 0;
                double totalProfit = 0;
                double lastMaxPrice = -1, lastMinPrice = -1;

                List<TradeRecord> records = new ArrayList<>();

                for (int right = 0; right < N; right++) {
                    long currentTs = timestamps[right];
                    double currentHigh = highs[right];
                    double currentLow = lows[right];
                    double currentClose = closes[right];

                    while (maxHead < maxTail && highs[maxDeque[maxTail - 1]] <= currentHigh) maxTail--;
                    maxDeque[maxTail++] = right;
                    while (minHead < minTail && lows[minDeque[minTail - 1]] >= currentLow) minTail--;
                    minDeque[minTail++] = right;

                    long windowStart = currentTs - windowUs;
                    while (left < right && timestamps[left] < windowStart) {
                        if (maxHead < maxTail && maxDeque[maxHead] == left) maxHead++;
                        if (minHead < minTail && minDeque[minHead] == left) minHead++;
                        left++;
                    }

                    if (right > 0) {
                        erSum += absChanges[right];
                        if (right > ER_LOOKBACK) erSum -= absChanges[right - ER_LOOKBACK];
                    }

                    if (right < warmup) continue;

                    double er = -1;
                    if (right >= ER_LOOKBACK && erSum > 0) {
                        double netChange = Math.abs(closes[right] - closes[right - ER_LOOKBACK]);
                        er = netChange / erSum;
                    }
                    boolean isRanging = er >= 0 && er < ER_RANGING;
                    boolean isTrending = er > ER_TRENDING;

                    if (isTrending) { skippedTrending++; continue; }
                    if (!isRanging || right - left < 60 || maxHead >= maxTail || minHead >= minTail) continue;

                    long epochSecond = timestamps[right] / (isMicro ? 1000000L : 1000L);
                    int hour = java.time.Instant.ofEpochSecond(epochSecond)
                            .atZone(ZoneId.systemDefault()).getHour();
                    boolean allowedHour = hour == 0 || hour == 1 || hour == 6 || hour == 7
                            || hour == 12 || hour == 13 || hour == 15
                            || hour == 19 || hour == 22 || hour == 23;
                    if (!allowedHour) { skippedByHour++; continue; }

                    int maxIdx = maxDeque[maxHead];
                    int minIdx = minDeque[minHead];
                    double windowMax = highs[maxIdx];
                    double windowMin = lows[minIdx];

                    boolean isHighExtreme = currentHigh >= windowMax;
                    boolean isLowExtreme = currentLow <= windowMin;
                    if (!isHighExtreme && !isLowExtreme) continue;

                    if (isHighExtreme && Math.abs(windowMax - lastMaxPrice) < 1e-8) {
                        skippedDuplicate++; continue;
                    }
                    if (isLowExtreme && Math.abs(windowMin - lastMinPrice) < 1e-8) {
                        skippedDuplicate++; continue;
                    }
                    if (isHighExtreme) lastMaxPrice = windowMax;
                    if (isLowExtreme) lastMinPrice = windowMin;

                    boolean bounced = false;
                    boolean brokeOut = false;
                    int bounceIdx = right;
                    double bouncePrice = currentClose;

                    for (int fwd = right + 1; fwd < N && (timestamps[fwd] - currentTs) <= bounceWindowUs; fwd++) {
                        if (isHighExtreme) {
                            if (closes[fwd] <= currentClose * (1 - bounceThreshold)) {
                                bounced = true;
                                bounceIdx = fwd;
                                bouncePrice = closes[fwd];
                                break;
                            }
                            if (closes[fwd] > currentClose * (1 + bounceThreshold)) {
                                brokeOut = true;
                                break;
                            }
                        } else {
                            if (closes[fwd] >= currentClose * (1 + bounceThreshold)) {
                                bounced = true;
                                bounceIdx = fwd;
                                bouncePrice = closes[fwd];
                                break;
                            }
                            if (closes[fwd] < currentClose * (1 - bounceThreshold)) {
                                brokeOut = true;
                                break;
                            }
                        }
                    }

                    if (!bounced) {
                        if (brokeOut) skippedBreakout++;
                        else skippedNoBounce++;
                        continue;
                    }

                    long settleTs = currentTs + settleUs;
                    int settleIdx = java.util.Arrays.binarySearch(
                            timestamps, bounceIdx, Math.min(N - 1, bounceIdx + settleSearchRange), settleTs);
                    if (settleIdx < 0) settleIdx = -settleIdx - 1;
                    if (settleIdx >= N) continue;
                    double settlePrice = closes[settleIdx];

                    TradeRecord rec = new TradeRecord();
                    rec.setOpenTimestamp(currentTs);
                    rec.setOpenTime(formatTimestamp(currentTs));
                    rec.setOpenPrice(bouncePrice);
                    rec.setHigh20min(windowMax);
                    rec.setHigh20minTime(formatTimestamp(timestamps[maxIdx]));
                    rec.setLow20min(windowMin);
                    rec.setLow20minTime(formatTimestamp(timestamps[minIdx]));
                    rec.setTenMinuteLaterPrice(settlePrice);
                    rec.setTenMinuteLaterTime(formatTimestamp(settleTs));
                    rec.setDelayedOpenTimestamp(timestamps[bounceIdx]);
                    rec.setDelayedOpenPrice(bouncePrice);
                    rec.setCoolingSeconds((int)((timestamps[bounceIdx] - currentTs) / (isMicro ? 1000000L : 1000L)));

                    boolean isWin;
                    double rawProfit;
                    if (isHighExtreme) {
                        rec.setDirection("空单");
                        rec.setTriggerExtreme("最高价 " + String.format("%.4f", windowMax));
                        rec.setTriggerExtremeTime(formatTimestamp(timestamps[maxIdx]));
                        rawProfit = bouncePrice - settlePrice;
                        isWin = rawProfit > 0;
                    } else {
                        rec.setDirection("多单");
                        rec.setTriggerExtreme("最低价 " + String.format("%.4f", windowMin));
                        rec.setTriggerExtremeTime(formatTimestamp(timestamps[minIdx]));
                        rawProfit = settlePrice - bouncePrice;
                        isWin = rawProfit > 0;
                    }

                    rec.setProfit(isWin ? winReturn : loseReturn);
                    rec.setProfitPercent((rawProfit / bouncePrice) * 100);
                    rec.setWin(isWin);

                    records.add(rec);
                    totalTrades++;
                    totalProfit += rec.getProfit();
                    if (isWin) {
                        wins++;
                        consecutiveLose = 0;
                    } else {
                        consecutiveLose++;
                        if (consecutiveLose > maxConsecutiveLose) maxConsecutiveLose = consecutiveLose;
                    }
                }

                double winRate = totalTrades > 0 ? wins * 100.0 / totalTrades : 0;
                double avgPnl = totalTrades > 0 ? totalProfit / totalTrades : 0;
                double beRate = 1.0 / (1.0 + winReturn / Math.abs(loseReturn)) * 100;

                System.out.printf("  %-10s %8d %8.2f%% %+10.2f %+10.4f %8d %8d %8d %8d\n",
                        String.format("%.2f%%", bounceThreshold * 100),
                        totalTrades, winRate, totalProfit, avgPnl,
                        skippedNoBounce, skippedBreakout, skippedTimeout, maxConsecutiveLose);

                summaryCsv.append(String.format("%s,%.2f,%d,%d,%d,%.2f,%+.2f,%+.4f,%d,%d,%d,%d,%d,%d,%d\n",
                        planName, bounceThreshold * 100, settleMinutes, winMin,
                        totalTrades, winRate, totalProfit, avgPnl,
                        skippedNoBounce, skippedBreakout, skippedTimeout,
                        skippedByHour, skippedDuplicate, skippedTrending, maxConsecutiveLose));

                if (!records.isEmpty() && bounceThreshold == 0.0003) {
                    String outputPath = "D:\\回踩确认_" + planName + ".xlsx";
                    saveToExcelWithSettle(records, outputPath, planName + "_回踩0.03%",
                            settleMinutes, winReturn, beRate,
                            totalTrades, wins, winRate, totalProfit, maxConsecutiveLose);
                }
            }
            System.out.println();
        }

        String summaryPath = "D:\\回踩确认_网格搜索.csv";
        java.nio.file.Files.write(java.nio.file.Paths.get(summaryPath),
                summaryCsv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("[3/3] 汇总已保存: " + summaryPath);

        long t1 = System.currentTimeMillis();
        System.out.println("\n总耗时: " + formatTime(t1 - t0));
    }

    @Test
    public void testFinalThreeStrategies() throws IOException {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║        最终三方案 — 回踩确认回测                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  方案1: 10分结算 | 25分极值 | 0.05%回踩 | 胜+4.00 亏-5   ║");
        System.out.println("║  方案2: 30分结算 | 40分极值 | 0.01%回踩 | 胜+4.25 亏-5   ║");
        System.out.println("║  方案3: 1H 结算 | 40分极值 | 0.01%回踩 | 胜+4.25 亏-5   ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        long t0 = System.currentTimeMillis();

        System.out.println("[1/3] 加载全量数据...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        if (sample.isEmpty()) { System.out.println("数据库无数据"); return; }
        boolean isMicro = sample.get(0).getTimestamp() > 1000000000000000L;

        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(0L, Long.MAX_VALUE);
        if (allData.isEmpty()) { System.out.println("数据库无数据"); return; }

        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        int N = allData.size();
        System.out.println("  加载完成: " + formatNumber(N) + " 条");

        System.out.println("  预计算原始数组...");
        long[] timestamps = new long[N];
        double[] closes = new double[N];
        double[] highs = new double[N];
        double[] lows = new double[N];

        for (int i = 0; i < N; i++) {
            EthKlineSecond d = allData.get(i);
            timestamps[i] = d.getTimestamp();
            closes[i] = parseDouble(d.getClose());
            highs[i] = parseDouble(d.getHigh());
            lows[i] = parseDouble(d.getLow());
        }

        double[] absChanges = new double[N];
        absChanges[0] = 0;
        for (int i = 1; i < N; i++) {
            absChanges[i] = Math.abs(closes[i] - closes[i - 1]);
        }

        allData = null;
        System.gc();

        int bounceWindowSec = 120;

        String[][] plans = {
                {"最终方案1_10分结算_25分极值_0.05回踩", "25", "10", "4.0", "-5.0", "0.0005"},
                {"最终方案2_30分结算_40分极值_0.01回踩", "40", "30", "4.25", "-5.0", "0.0001"},
                {"最终方案3_1H结算_40分极值_0.01回踩",   "40", "60", "4.25", "-5.0", "0.0001"},
        };

        System.out.println("\n[2/3] 执行三方案回测...");
        System.out.println();

        for (String[] plan : plans) {
            String planName = plan[0];
            int winMin = Integer.parseInt(plan[1]);
            int settleMinutes = Integer.parseInt(plan[2]);
            double winReturn = Double.parseDouble(plan[3]);
            double loseReturn = Double.parseDouble(plan[4]);
            double bounceThreshold = Double.parseDouble(plan[5]);
            long settleUs = settleMinutes * 60L * (isMicro ? 1000000L : 1000L);
            long windowUs = winMin * 60L * (isMicro ? 1000000L : 1000L);
            int warmup = Math.max(ER_LOOKBACK, Math.max(winMin * 60, settleMinutes * 60));
            int settleSearchRange = Math.min(N - 1, settleMinutes * 60 + 1200);
            long bounceWindowUs = bounceWindowSec * (isMicro ? 1000000L : 1000L);

            int[] maxDeque = new int[N];
            int[] minDeque = new int[N];
            int maxHead = 0, maxTail = 0, minHead = 0, minTail = 0;
            int left = 0;
            double erSum = 0;

            int totalTrades = 0, wins = 0;
            int skippedTrending = 0, skippedDuplicate = 0, skippedByHour = 0;
            int skippedNoBounce = 0, skippedBreakout = 0;
            int consecutiveLose = 0, maxConsecutiveLose = 0;
            double totalProfit = 0;
            double lastMaxPrice = -1, lastMinPrice = -1;

            List<TradeRecord> records = new ArrayList<>();

            System.out.println("  " + planName + " ...");

            for (int right = 0; right < N; right++) {
                long currentTs = timestamps[right];
                double currentHigh = highs[right];
                double currentLow = lows[right];
                double currentClose = closes[right];

                while (maxHead < maxTail && highs[maxDeque[maxTail - 1]] <= currentHigh) maxTail--;
                maxDeque[maxTail++] = right;
                while (minHead < minTail && lows[minDeque[minTail - 1]] >= currentLow) minTail--;
                minDeque[minTail++] = right;

                long windowStart = currentTs - windowUs;
                while (left < right && timestamps[left] < windowStart) {
                    if (maxHead < maxTail && maxDeque[maxHead] == left) maxHead++;
                    if (minHead < minTail && minDeque[minHead] == left) minHead++;
                    left++;
                }

                if (right > 0) {
                    erSum += absChanges[right];
                    if (right > ER_LOOKBACK) erSum -= absChanges[right - ER_LOOKBACK];
                }

                if (right < warmup) continue;

                double er = -1;
                if (right >= ER_LOOKBACK && erSum > 0) {
                    double netChange = Math.abs(closes[right] - closes[right - ER_LOOKBACK]);
                    er = netChange / erSum;
                }
                boolean isRanging = er >= 0 && er < ER_RANGING;
                boolean isTrending = er > ER_TRENDING;

                if (isTrending) { skippedTrending++; continue; }
                if (!isRanging || right - left < 60 || maxHead >= maxTail || minHead >= minTail) continue;

                long epochSecond = timestamps[right] / (isMicro ? 1000000L : 1000L);
                int hour = java.time.Instant.ofEpochSecond(epochSecond)
                        .atZone(ZoneId.systemDefault()).getHour();
                boolean allowedHour = hour == 0 || hour == 1 || hour == 6 || hour == 7
                        || hour == 12 || hour == 13 || hour == 15
                        || hour == 19 || hour == 22 || hour == 23;
                if (!allowedHour) { skippedByHour++; continue; }

                int maxIdx = maxDeque[maxHead];
                int minIdx = minDeque[minHead];
                double windowMax = highs[maxIdx];
                double windowMin = lows[minIdx];

                boolean isHighExtreme = currentHigh >= windowMax;
                boolean isLowExtreme = currentLow <= windowMin;
                if (!isHighExtreme && !isLowExtreme) continue;

                if (isHighExtreme && Math.abs(windowMax - lastMaxPrice) < 1e-8) {
                    skippedDuplicate++; continue;
                }
                if (isLowExtreme && Math.abs(windowMin - lastMinPrice) < 1e-8) {
                    skippedDuplicate++; continue;
                }
                if (isHighExtreme) lastMaxPrice = windowMax;
                if (isLowExtreme) lastMinPrice = windowMin;

                boolean bounced = false;
                boolean brokeOut = false;
                int bounceIdx = right;
                double bouncePrice = currentClose;

                for (int fwd = right + 1; fwd < N && (timestamps[fwd] - currentTs) <= bounceWindowUs; fwd++) {
                    if (isHighExtreme) {
                        if (closes[fwd] <= currentClose * (1 - bounceThreshold)) {
                            bounced = true; bounceIdx = fwd; bouncePrice = closes[fwd]; break;
                        }
                        if (closes[fwd] > currentClose * (1 + bounceThreshold)) {
                            brokeOut = true; break;
                        }
                    } else {
                        if (closes[fwd] >= currentClose * (1 + bounceThreshold)) {
                            bounced = true; bounceIdx = fwd; bouncePrice = closes[fwd]; break;
                        }
                        if (closes[fwd] < currentClose * (1 - bounceThreshold)) {
                            brokeOut = true; break;
                        }
                    }
                }

                if (!bounced) {
                    if (brokeOut) skippedBreakout++;
                    else skippedNoBounce++;
                    continue;
                }

                long settleTs = currentTs + settleUs;
                int settleIdx = java.util.Arrays.binarySearch(
                        timestamps, bounceIdx, Math.min(N - 1, bounceIdx + settleSearchRange), settleTs);
                if (settleIdx < 0) settleIdx = -settleIdx - 1;
                if (settleIdx >= N) continue;
                double settlePrice = closes[settleIdx];

                TradeRecord rec = new TradeRecord();
                rec.setOpenTimestamp(currentTs);
                rec.setOpenTime(formatTimestamp(currentTs));
                rec.setOpenPrice(bouncePrice);
                rec.setHigh20min(windowMax);
                rec.setHigh20minTime(formatTimestamp(timestamps[maxIdx]));
                rec.setLow20min(windowMin);
                rec.setLow20minTime(formatTimestamp(timestamps[minIdx]));
                rec.setTenMinuteLaterPrice(settlePrice);
                rec.setTenMinuteLaterTime(formatTimestamp(settleTs));
                rec.setDelayedOpenTimestamp(timestamps[bounceIdx]);
                rec.setDelayedOpenPrice(bouncePrice);
                rec.setCoolingSeconds((int)((timestamps[bounceIdx] - currentTs) / (isMicro ? 1000000L : 1000L)));

                boolean isWin;
                double rawProfit;
                if (isHighExtreme) {
                    rec.setDirection("空单");
                    rec.setTriggerExtreme("最高价 " + String.format("%.4f", windowMax));
                    rec.setTriggerExtremeTime(formatTimestamp(timestamps[maxIdx]));
                    rawProfit = bouncePrice - settlePrice;
                    isWin = rawProfit > 0;
                } else {
                    rec.setDirection("多单");
                    rec.setTriggerExtreme("最低价 " + String.format("%.4f", windowMin));
                    rec.setTriggerExtremeTime(formatTimestamp(timestamps[minIdx]));
                    rawProfit = settlePrice - bouncePrice;
                    isWin = rawProfit > 0;
                }

                rec.setProfit(isWin ? winReturn : loseReturn);
                rec.setProfitPercent((rawProfit / bouncePrice) * 100);
                rec.setWin(isWin);

                records.add(rec);
                totalTrades++;
                totalProfit += rec.getProfit();
                if (isWin) {
                    wins++;
                    consecutiveLose = 0;
                } else {
                    consecutiveLose++;
                    if (consecutiveLose > maxConsecutiveLose) maxConsecutiveLose = consecutiveLose;
                }
            }

            double winRate = totalTrades > 0 ? wins * 100.0 / totalTrades : 0;
            double beRate = 1.0 / (1.0 + winReturn / Math.abs(loseReturn)) * 100;

            System.out.println("    交易:" + totalTrades + " 胜率:" + String.format("%.2f%%", winRate)
                    + " 盈亏:" + String.format("%+.2fU", totalProfit)
                    + " 最大连亏:" + maxConsecutiveLose
                    + " 突破取消:" + skippedBreakout
                    + " 无回踩:" + skippedNoBounce);

            String outputPath = "D:\\" + planName + ".xlsx";
            saveToExcelWithSettle(records, outputPath, planName,
                    settleMinutes, winReturn, beRate,
                    totalTrades, wins, winRate, totalProfit, maxConsecutiveLose);
            System.out.println("    → 已保存: " + outputPath);
        }

        long t1 = System.currentTimeMillis();
        System.out.println("\n[3/3] 三方案回测完成");
        System.out.println("总耗时: " + formatTime(t1 - t0));
    }

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

}