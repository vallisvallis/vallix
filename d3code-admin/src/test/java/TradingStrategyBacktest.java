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
        V6("v6(2h/1h)", 120, 60, 1, 0, false);

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
     * 运行V1趋势过滤策略回测（从2026-06-1至今）
     */
    @Test
    public void testTradingStrategy() throws IOException {
        runBacktest(VallisStrategy.V1);
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

}