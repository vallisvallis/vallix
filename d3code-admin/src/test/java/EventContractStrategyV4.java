package com.alphay.boot.web.test;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.mapper.EthKlineSecondMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 币安事件合约策略 V4
 * 基于趋势过滤 + 多时间框架预测
 * 核心思想：只在趋势明确时开单，横盘不开
 * 预测方向：判断开仓后 N分钟 价格相对于开仓价的高低
 *
 * @author d3code
 */
@SpringBootTest
public class EventContractStrategyV4 {

    @Autowired
    private EthKlineSecondMapper ethKlineSecondMapper;

    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    private String colorProfit(double p) {
        String c = p > 0 ? GREEN : p < 0 ? RED : "";
        String r = p > 0 || p < 0 ? RESET : "";
        return String.format("%s%+8.4f%s", c, p, r);
    }

    private String colorWin(boolean win) {
        return win ? GREEN + "√" + RESET : RED + "×" + RESET;
    }

    private String formatTs(long ts, boolean isMicro) {
        long ms = isMicro ? ts / 1000 : ts;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }

    /**
     * 事件合约到期时间选项
     */
    enum ExpiryType {
        T_10MIN(10, "10分钟"),
        T_30MIN(30, "30分钟"),
        T_60MIN(60, "1小时");

        final int minutes;
        final String label;
        ExpiryType(int m, String l) { minutes = m; label = l; }
    }

    /**
     * 策略参数
     */
    enum StrategyParams {
        V4_BASELINE(
                "V4 趋势过滤基准",
                5,          // 趋势确认窗口 (分钟)
                0.003,      // 趋势阈值 (0.3%)
                20,         // 极值过滤窗口 (分钟)
                true,       // 只开趋势同向单
                true        // 横盘不开单
        );

        final String name;
        final int trendMinutes;
        final double trendThreshold;
        final int extremeWindow;
        final boolean onlyTrendAligned;
        final boolean skipSideways;

        StrategyParams(String name, int tm, double tt, int ew, boolean ota, boolean ss) {
            this.name = name;
            this.trendMinutes = tm;
            this.trendThreshold = tt;
            this.extremeWindow = ew;
            this.onlyTrendAligned = ota;
            this.skipSideways = ss;
        }
    }

    /**
     * 回测结果
     */
    static class BacktestResult {
        int total = 0;
        int correct = 0;
        int wrong = 0;
        double winRate;
        int longCorrect = 0, longWrong = 0;
        int shortCorrect = 0, shortWrong = 0;
        int trendAlignedCorrect = 0, trendAlignedWrong = 0;
        int trendAgainstCorrect = 0, trendAgainstWrong = 0;
    }

    /**
     * 回测一笔交易
     */
    static class TradeSignal {
        long openTs;       // 开仓时间戳
        String direction;  // 多/空
        double openPrice;
        double closePrice;
        boolean correct;   // 方向是否正确
        double trendChange;
        int expiryMinutes;
    }

    @Test
    public void backtestV4() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   事件合约策略 V4 回测                       ║");
        System.out.println("║   只做趋势明确，横盘不开单                   ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        StrategyParams params = StrategyParams.V4_BASELINE;
        System.out.println("📋 策略参数: " + params.name);
        System.out.println("  趋势确认窗口: " + params.trendMinutes + " 分钟");
        System.out.println("  趋势阈值: " + (params.trendThreshold * 100) + "%");
        System.out.println("  极值窗口: " + params.extremeWindow + " 分钟");
        System.out.println("  只开顺趋势单: " + params.onlyTrendAligned);
        System.out.println("  横盘不开单: " + params.skipSideways);
        System.out.println();

        // 加载全量数据
        List<EthKlineSecond> allData = ethKlineSecondMapper.selectList(new LambdaQueryWrapper<>());
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        if (allData.isEmpty()) {
            System.out.println("⚠️ 没有数据");
            return;
        }

        boolean isMicro = allData.get(0).getTimestamp() > 1000000000000000L;
        System.out.println("📊 数据范围: " + formatTs(allData.get(0).getTimestamp(), isMicro)
                + " ~ " + formatTs(allData.get(allData.size() - 1).getTimestamp(), isMicro));
        System.out.println("  总数据条数: " + allData.size());
        System.out.println();

        // 构建时间映射
        Map<Long, EthKlineSecond> dataMap = new HashMap<>(allData.size());
        List<Long> timestamps = new ArrayList<>(allData.size());
        for (EthKlineSecond d : allData) {
            dataMap.put(d.getTimestamp(), d);
            timestamps.add(d.getTimestamp());
        }

        // 对每个到期类型分别回测
        for (ExpiryType expiry : ExpiryType.values()) {
            backtestForExpiry(expiry, params, allData, timestamps, dataMap, isMicro);
        }
    }

    private void backtestForExpiry(ExpiryType expiry, StrategyParams params,
                                   List<EthKlineSecond> allData, List<Long> timestamps,
                                   Map<Long, EthKlineSecond> dataMap, boolean isMicro) {
        System.out.println("══════════════════════════════════════════════════");
        System.out.println("📌 到期时间: " + expiry.minutes + " 分钟 (" + expiry.label + ")");
        System.out.println();

        BacktestResult result = new BacktestResult();
        List<TradeSignal> allSignals = new ArrayList<>();
        List<TradeSignal> wrongSignals = new ArrayList<>();

        long trendUs = params.trendMinutes * 60 * 1_000_000L;
        long extremeUs = params.extremeWindow * 60 * 1_000_000L;
        long expiryUs = expiry.minutes * 60 * 1_000_000L;

        // 滑动窗口遍历
        int leftExtreme = 0;
        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        long lastMaxTs = -1, lastMinTs = -1;  // 极值去重：同一个极值只开一单

        for (int right = 0; right < allData.size(); right++) {
            EthKlineSecond cur = allData.get(right);
            long curTs = cur.getTimestamp();
            double curPrice = parseDouble(cur.getClose());

            // 维护20分钟极值窗口
            while (!maxDeque.isEmpty() && parseDouble(allData.get(maxDeque.peekLast()).getClose()) <= curPrice)
                maxDeque.pollLast();
            maxDeque.offerLast(right);
            while (!minDeque.isEmpty() && parseDouble(allData.get(minDeque.peekLast()).getClose()) >= curPrice)
                minDeque.pollLast();
            minDeque.offerLast(right);

            while (timestamps.get(leftExtreme) < curTs - extremeUs) {
                if (!maxDeque.isEmpty() && maxDeque.peekFirst() == leftExtreme)
                    maxDeque.pollFirst();
                if (!minDeque.isEmpty() && minDeque.peekFirst() == leftExtreme)
                    minDeque.pollFirst();
                leftExtreme++;
            }

            // 需要足够历史来判断趋势
            if (right < params.trendMinutes * 60) continue;
            // 极值窗口需要填满
            if (right - leftExtreme < params.extremeWindow * 60) continue;
            // 需要到期时间数据
            if (!dataMap.containsKey(curTs + expiryUs)) continue;

            // 获取极值
            if (maxDeque.isEmpty() || minDeque.isEmpty()) continue;
            int maxIdx = maxDeque.peekFirst(), minIdx = minDeque.peekFirst();
            long maxTs = timestamps.get(maxIdx);
            double maxPrice = parseDouble(allData.get(maxIdx).getClose());
            double minPrice = parseDouble(allData.get(minIdx).getClose());

            // === 判断开仓前趋势 ===
            long trendStartTs = curTs - trendUs;
            int tIdx = Collections.binarySearch(timestamps, trendStartTs);
            if (tIdx < 0) tIdx = -tIdx - 1;
            if (tIdx >= right) continue;
            double oldPrice = parseDouble(allData.get(tIdx).getClose());
            double trendChange = (curPrice - oldPrice) / oldPrice;
            boolean trendUp = trendChange > params.trendThreshold;
            boolean trendDown = trendChange < -params.trendThreshold;

            // 横盘处理
            if (params.skipSideways && !trendUp && !trendDown) {
                continue;
            }

            // === 开仓信号 ===
            // 多单：当前低点在极值窗口，且极值未用过
            if (curTs - timestamps.get(minIdx) >= 60_000_000L &&
                    curTs - timestamps.get(minIdx) <= 180_000_000L &&
                    timestamps.get(minIdx) != lastMinTs) {

                boolean canOpen = !params.onlyTrendAligned || !trendDown;
                if (canOpen) {
                    lastMinTs = timestamps.get(minIdx);
                    // 多单：预计未来上涨 → 到期价 > 开仓价
                    EthKlineSecond expiryK = dataMap.get(curTs + expiryUs);
                    if (expiryK != null) {
                        double expiryPrice = parseDouble(expiryK.getClose());
                        boolean correct = expiryPrice > curPrice;
                        TradeSignal s = new TradeSignal();
                        s.openTs = curTs;
                        s.direction = "多单";
                        s.openPrice = curPrice;
                        s.closePrice = expiryPrice;
                        s.correct = correct;
                        s.trendChange = trendChange;
                        s.expiryMinutes = expiry.minutes;
                        allSignals.add(s);
                        result.total++;
                        if (correct) {
                            result.correct++;
                            if (trendUp) result.trendAlignedCorrect++;
                            else result.trendAgainstCorrect++;
                            if ("多单".equals(s.direction)) result.longCorrect++;
                        } else {
                            result.wrong++;
                            wrongSignals.add(s);
                            if (trendUp) result.trendAlignedWrong++;
                            else result.trendAgainstWrong++;
                            if ("多单".equals(s.direction)) result.longWrong++;
                        }
                    }
                }
            }

            // 空单：当前高点在极值窗口，且极值未用过
            if (curTs - maxTs >= 60_000_000L &&
                    curTs - maxTs <= 180_000_000L &&
                    maxTs != lastMaxTs) {

                boolean canOpen = !params.onlyTrendAligned || !trendUp;
                if (canOpen) {
                    lastMaxTs = maxTs;
                    // 空单：预计未来下跌 → 到期价 < 开仓价
                    EthKlineSecond expiryK = dataMap.get(curTs + expiryUs);
                    if (expiryK != null) {
                        double expiryPrice = parseDouble(expiryK.getClose());
                        boolean correct = expiryPrice < curPrice;
                        TradeSignal s = new TradeSignal();
                        s.openTs = curTs;
                        s.direction = "空单";
                        s.openPrice = curPrice;
                        s.closePrice = expiryPrice;
                        s.correct = correct;
                        s.trendChange = trendChange;
                        s.expiryMinutes = expiry.minutes;
                        allSignals.add(s);
                        result.total++;
                        if (correct) {
                            result.correct++;
                            if (trendDown) result.trendAlignedCorrect++;
                            else result.trendAgainstCorrect++;
                            if ("空单".equals(s.direction)) result.shortCorrect++;
                        } else {
                            result.wrong++;
                            wrongSignals.add(s);
                            if (trendDown) result.trendAlignedWrong++;
                            else result.trendAgainstWrong++;
                            if ("空单".equals(s.direction)) result.shortWrong++;
                        }
                    }
                }
            }
        }

        result.winRate = result.total > 0 ? (double)result.correct / result.total * 100 : 0;

        // 打印结果
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║              回测结果汇总                     ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 总信号数: %-24d                 ║", result.total));
        System.out.println(String.format("║ 正确: %-5d 错误: %-5d 胜率: %5.1f%%         ║",
                result.correct, result.wrong, result.winRate));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 多单: %-2d对/%-2d错 = %5.1f%%   空单: %-2d对/%-2d错 = %5.1f%% ║",
                result.longCorrect, result.longWrong,
                (result.longCorrect + result.longWrong) > 0 ?
                        100.0 * result.longCorrect / (result.longCorrect + result.longWrong) : 0,
                result.shortCorrect, result.shortWrong,
                (result.shortCorrect + result.shortWrong) > 0 ?
                        100.0 * result.shortCorrect / (result.shortCorrect + result.shortWrong) : 0));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 顺趋势: %-2d对/%-2d错 = %5.1f%%   逆趋势: %-2d对/%-2d错 = %5.1f%% ║",
                result.trendAlignedCorrect, result.trendAlignedWrong,
                (result.trendAlignedCorrect + result.trendAlignedWrong) > 0 ?
                        100.0 * result.trendAlignedCorrect / (result.trendAlignedCorrect + result.trendAlignedWrong) : 0,
                result.trendAgainstCorrect, result.trendAgainstWrong,
                (result.trendAgainstCorrect + result.trendAgainstWrong) > 0 ?
                        100.0 * result.trendAgainstCorrect / (result.trendAgainstCorrect + result.trendAgainstWrong) : 0));
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // 最近错误信号
        if (!wrongSignals.isEmpty() && wrongSignals.size() > 0) {
            System.out.println("📋 最近" + Math.min(10, wrongSignals.size()) + "个错误信号:");
            System.out.println(" 序号  时间              方向  开仓价   到期价   趋势变化  结果");
            int count = 0;
            for (int i = wrongSignals.size() - 1; i >= 0 && count < 10; i--, count++) {
                TradeSignal s = wrongSignals.get(i);
                System.out.println(String.format(" %2d   %s  %s  %.2f  %.2f  %+6.2f%%  %s",
                        count + 1, formatTs(s.openTs, isMicro),
                        s.direction.substring(0, 2), s.openPrice, s.closePrice,
                        s.trendChange * 100, colorWin(s.correct)));
            }
            System.out.println();
        }
    }

    private double parseDouble(String s) {
        return Double.parseDouble(s);
    }
}