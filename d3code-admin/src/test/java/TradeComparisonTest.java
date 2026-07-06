package com.alphay.boot.web.test;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.api.domain.EthTradeRecord;
import com.alphay.boot.bpm.mapper.EthKlineSecondMapper;
import com.alphay.boot.bpm.mapper.EthTradeRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

/**
 * 实时交易 vs 回测 对比测试
 *
 * 功能：
 * 1. testRealTimeStats() - 统计实时交易库中已有数据
 * 2. compareRealTimeVsBacktest() - 逐一对比实时开单与回测策略结果
 * 3. diagnoseMissingTrades() - 诊断"回测没生成但是实时开了"的原因
 *
 * 目的：验证实时交易逻辑与回测逻辑是否一致，找出差异原因
 *
 * @author d3code
 */
@SpringBootTest
public class TradeComparisonTest {

    // ==================== 依赖注入 ====================

    @Autowired
    private EthTradeRecordMapper tradeRecordMapper;

    @Autowired
    private EthKlineSecondMapper ethKlineSecondMapper;

    // ==================== 常量定义（微秒）====================

    private static final long TWENTY_MINUTES_US = 1_200_000_000L;  // 20分钟极值窗口
    private static final long TEN_MINUTES_US = 600_000_000L;       // 10分钟结算
    private static final long ONE_MINUTE_US = 60_000_000L;         // 1分钟
    private static final long THREE_MINUTES_US = 180_000_000L;     // 3分钟
    private static final long FIVE_MINUTES_US = 300_000_000L;      // 5分钟趋势窗口
    private static final double TREND_THRESHOLD = 0.003;           // 趋势阈值 0.3%

    // ==================== 颜色常量（终端输出）====================

    private static final String GREEN = "\033[32m";   // 绿色（盈利）
    private static final String RED = "\033[31m";     // 红色（亏损）
    private static final String RESET = "\033[0m";    // 重置颜色

    // ==================== 内部数据模型 ====================

    /**
     * 回测交易结果
     */
    static class BacktestTrade {
        String direction;       // 方向
        long openTs;            // 开单时间戳（微秒）
        double openPrice;       // 开单价
        double maxPrice, minPrice;  // 窗口极值
        long maxTs, minTs;      // 极值时间戳
        double settlePrice;    // 结算价
        long settleTs;         // 结算时间
        double profit;         // 盈亏
    }

    // ==================== 工具方法 ====================

    /** 给盈亏上色：绿色盈利，红色亏损 */
    private String colorProfit(double p) {
        String c = p > 0 ? GREEN : p < 0 ? RED : "";
        String r = p > 0 || p < 0 ? RESET : "";
        return String.format("%s%+8.4f%s", c, p, r);
    }

    /** 安全解析Double字符串 */
    private double parseDouble(String value) {
        try {
            if (value == null) return 0;
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 格式化数字（千分位分隔）*/
    private String formatNum(long num) {
        return String.format("%,d", num);
    }

    /** 格式化时间戳 */
    private String formatTs(long ts, boolean isMicro) {
        long ms = isMicro ? ts / 1000 : ts;
        Date d = new Date(ms);
        return String.format("%tF %tT", d, d);
    }

    // ==================== 测试方法 ====================

    /**
     * 统计实时交易数据库中的已有数据
     * 输出：总盈亏、胜率、盈利因子、最大盈亏、小时分布、连胜连亏
     */
    @Test
    public void testRealTimeStats() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║         实时交易详情统计                      ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        List<EthTradeRecord> allTrades = tradeRecordMapper.selectList(null);
        if (allTrades.isEmpty()) {
            System.out.println("⚠️ 没有交易记录");
            return;
        }

        long minTs = allTrades.stream().mapToLong(EthTradeRecord::getOpenTimestamp).min().orElse(0);
        long maxTs = allTrades.stream().mapToLong(EthTradeRecord::getOpenTimestamp).max().orElse(0);
        boolean m = minTs > 1000000000000000L;
        System.out.println("📅 时间范围: " + formatTs(minTs, m) + " ~ " + formatTs(maxTs, m));
        System.out.println("📊 总交易: " + allTrades.size() + " 笔\n");

        // 多空分类统计
        List<EthTradeRecord> longs = new ArrayList<>();
        List<EthTradeRecord> shorts = new ArrayList<>();
        for (EthTradeRecord t : allTrades) {
            if ("多单".equals(t.getDirection())) longs.add(t);
            else shorts.add(t);
        }

        printDirectionStats("多单", longs);
        printDirectionStats("空单", shorts);

        // 整体统计
        double totalProfit = 0, totalWin = 0, totalLoss = 0;
        int winCount = 0, lossCount = 0;
        double maxWin = Double.MIN_VALUE, maxLoss = Double.MAX_VALUE;
        EthTradeRecord bestTrade = null, worstTrade = null;

        for (EthTradeRecord t : allTrades) {
            double p = t.getProfit() != null ? Double.parseDouble(t.getProfit()) : 0;
            totalProfit += p;
            if (p > 0) { totalWin += p; winCount++; }
            else if (p < 0) { totalLoss += Math.abs(p); lossCount++; }
            if (p > maxWin) { maxWin = p; bestTrade = t; }
            if (p < maxLoss) { maxLoss = p; worstTrade = t; }
        }

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║              整体表现                         ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 总盈亏: %s                  ║", colorProfit(totalProfit)));
        System.out.println(String.format("║ 胜率: %d/%d = %.1f%%                       ║",
                winCount, winCount + lossCount,
                (winCount + lossCount) > 0 ? 100.0 * winCount / (winCount + lossCount) : 0));
        System.out.println(String.format("║ 盈利因子: %.2f (总盈%.2f / 总亏%.2f)       ║",
                totalLoss > 0 ? totalWin / totalLoss : 0, totalWin, totalLoss));
        System.out.println(String.format("║ 平均盈亏: %+.4f                           ║",
                allTrades.size() > 0 ? totalProfit / allTrades.size() : 0));
        System.out.println(String.format("║ 最大盈利: %+.4f                           ║", maxWin));
        System.out.println(String.format("║ 最大亏损: %+.4f                           ║", maxLoss));
        System.out.println("╚══════════════════════════════════════════════╝\n");

        // 最佳/最差交易
        if (bestTrade != null) {
            System.out.println("🏆 最佳交易: " + formatTs(bestTrade.getOpenTimestamp(), m)
                    + " " + bestTrade.getDirection() + " 开单价" + bestTrade.getOpenPrice()
                    + " " + colorProfit(maxWin));
        }
        if (worstTrade != null) {
            System.out.println("💀 最差交易: " + formatTs(worstTrade.getOpenTimestamp(), m)
                    + " " + worstTrade.getDirection() + " 开单价" + worstTrade.getOpenPrice()
                    + " " + colorProfit(maxLoss));
        }

        // 小时分布
        System.out.println("\n┌──────┬──────┬──────────┬──────────┐");
        System.out.println("│ 时段  │ 笔数 │  总盈亏   │  胜率    │");
        System.out.println("├──────┼──────┼──────────┼──────────┤");
        for (int h = 0; h < 24; h++) {
            final int hour = h;
            List<EthTradeRecord> hourTrades = new ArrayList<>();
            for (EthTradeRecord t : allTrades) {
                long ms = m ? t.getOpenTimestamp() / 1000 : t.getOpenTimestamp();
                if (new Date(ms).getHours() == hour) hourTrades.add(t);
            }
            if (hourTrades.isEmpty()) continue;

            double hp = 0;
            int hw = 0;
            for (EthTradeRecord t : hourTrades) {
                double p = t.getProfit() != null ? Double.parseDouble(t.getProfit()) : 0;
                hp += p;
                if (p > 0) hw++;
            }
            double hwr = 100.0 * hw / hourTrades.size();
            System.out.println(String.format("│ %02d:00 │ %4d │ %+8.2f │ %5.1f%%   │",
                    hour, hourTrades.size(), hp, hwr));
        }
        System.out.println("└──────┴──────┴──────────┴──────────┘");

        // 连胜/连亏分析
        System.out.println("\n📈 连续盈亏分析:");
        int maxConsecutiveWins = 0, maxConsecutiveLosses = 0;
        int curWins = 0, curLosses = 0;
        for (EthTradeRecord t : allTrades) {
            double p = t.getProfit() != null ? Double.parseDouble(t.getProfit()) : 0;
            if (p > 0) { curWins++; curLosses = 0; maxConsecutiveWins = Math.max(maxConsecutiveWins, curWins); }
            else if (p < 0) { curLosses++; curWins = 0; maxConsecutiveLosses = Math.max(maxConsecutiveLosses, curLosses); }
            else { curWins = 0; curLosses = 0; }
        }
        System.out.println("  最长连胜: " + maxConsecutiveWins + " 笔");
        System.out.println("  最长连亏: " + maxConsecutiveLosses + " 笔");
    }

    /** 打印方向统计（多单/空单的胜率与盈亏）*/
    private void printDirectionStats(String label, List<EthTradeRecord> trades) {
        double total = 0, totalWin = 0;
        int win = 0, lose = 0;
        for (EthTradeRecord t : trades) {
            double p = t.getProfit() != null ? Double.parseDouble(t.getProfit()) : 0;
            total += p;
            if (p > 0) { totalWin += p; win++; }
            else if (p < 0) lose++;
        }
        int totalTrades = win + lose;
        double wr = totalTrades > 0 ? 100.0 * win / totalTrades : 0;
        double avg = totalTrades > 0 ? total / totalTrades : 0;

        System.out.println(String.format("📌 %s: %d笔 | 胜率 %.1f%% | 总盈亏 %+.4f | 均盈 %+.4f",
                label, totalTrades, wr, total, avg));
    }

    /**
     * 实时交易 vs 回测 对比分析
     * 流程：加载实时交易 → 加载K线数据 → 回测策略 → 逐笔对比
     * 对比维度：开单价、结算价、盈亏是否一致
     */
    @Test
    public void compareRealTimeVsBacktest() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║       实时交易 vs 回测 对比分析                 ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        long t0 = System.currentTimeMillis();

        // 1. 加载实时交易记录
        System.out.println("[1/4] 加载实时交易记录...");
        List<EthTradeRecord> allRealTrades = tradeRecordMapper.selectList(null);
        System.out.println("  实时交易(全部): " + allRealTrades.size() + " 笔");

        // 诊断：打印实际交易时间范围
        long minTs = allRealTrades.stream().mapToLong(EthTradeRecord::getOpenTimestamp).min().orElse(0);
        long maxTs = allRealTrades.stream().mapToLong(EthTradeRecord::getOpenTimestamp).max().orElse(0);
        boolean tsIsMicro = minTs > 1000000000000000L;
        System.out.println("  交易时间范围: " + formatTs(minTs, tsIsMicro) + " ~ " + formatTs(maxTs, tsIsMicro));
        System.out.println("  最早时间戳: " + minTs + "  最晚时间戳: " + maxTs);

        long filterStart = 1782655200000000L;
        List<EthTradeRecord> realTrades = new ArrayList<>();
        for (EthTradeRecord t : allRealTrades) {
            if (t.getOpenTimestamp() >= filterStart) {
                realTrades.add(t);
            }
        }
        System.out.println("  实时交易(7/1 22:10起): " + realTrades.size() + " 笔");

        if (realTrades.isEmpty()) {
            System.out.println("⚠️ 没有实时交易记录");
            return;
        }

        // 2. 确定时间范围，加载K线数据
        System.out.println("[2/4] 加载K线数据...");
        List<EthKlineSecond> sample = ethKlineSecondMapper.selectRecent(1);
        boolean isMicro = !sample.isEmpty() && sample.get(0).getTimestamp() > 1000000000000000L;

        long firstTradeTs = realTrades.stream().mapToLong(EthTradeRecord::getOpenTimestamp).min().orElse(0);
        long lastTradeTs = realTrades.stream().mapToLong(EthTradeRecord::getOpenTimestamp).max().orElse(0);
        long loadStart = firstTradeTs - TWENTY_MINUTES_US; // 仅加载20分钟窗口所需历史
        long loadEnd = lastTradeTs + TEN_MINUTES_US; // 结算需要10分钟

        List<EthKlineSecond> allData = ethKlineSecondMapper.selectByTimeRange(loadStart, loadEnd);
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        System.out.println("  时间范围: " + formatTs(loadStart, isMicro) + " ~ " + formatTs(loadEnd, isMicro));
        System.out.println("  K线数据: " + formatNum(allData.size()) + " 条");

        // 3. 运行回测策略
        System.out.println("[3/4] 运行回测策略...");
        Map<Long, EthKlineSecond> dataMap = new HashMap<>(allData.size());
        List<Long> timestamps = new ArrayList<>(allData.size());
        for (EthKlineSecond d : allData) {
            dataMap.put(d.getTimestamp(), d);
            timestamps.add(d.getTimestamp());
        }
        Map<Long, String> blockedReasons = new HashMap<>();
        List<BacktestTrade> allBacktestTrades = runBacktest(allData, timestamps, dataMap, blockedReasons);

        // 过滤：仅保留实时交易时间范围内的回测交易
        List<BacktestTrade> backtestTrades = new ArrayList<>();
        for (BacktestTrade bt : allBacktestTrades) {
            if (bt.openTs >= firstTradeTs && bt.openTs <= lastTradeTs) {
                backtestTrades.add(bt);
            }
        }
        System.out.println("  回测交易(全部): " + allBacktestTrades.size() + " 笔, 范围内: " + backtestTrades.size() + " 笔");

        // 4. 对比
        System.out.println("[4/4] 对比分析...\n");
        compare(realTrades, backtestTrades, firstTradeTs, lastTradeTs, isMicro, blockedReasons,
                allData, timestamps, dataMap);

        System.out.println("\n总耗时: " + (System.currentTimeMillis() - t0) / 1000 + " 秒");
    }

    /**
     * 运行回测策略（与实时策略逻辑一致）
     * 使用滑动窗口 + 单调队列找极值，5分钟趋势过滤，极值去重
     * 记录被过滤的原因（趋势过滤、极值重复）供后续诊断
     */
    private List<BacktestTrade> runBacktest(List<EthKlineSecond> allData, List<Long> timestamps,
                                            Map<Long, EthKlineSecond> dataMap,
                                            Map<Long, String> blockedReasons) {
        List<BacktestTrade> trades = new ArrayList<>();
        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        int left = 0;
        long lastShortTs = -1, lastLongTs = -1, lastTradeTs = -1;
        int skippedByTrend = 0;

        for (int right = 0; right < allData.size(); right++) {
            EthKlineSecond cur = allData.get(right);
            long curTs = cur.getTimestamp();
            double curPrice = parseDouble(cur.getClose());

            while (!maxDeque.isEmpty() && parseDouble(allData.get(maxDeque.peekLast()).getClose()) <= curPrice)
                maxDeque.pollLast();
            maxDeque.offerLast(right);
            while (!minDeque.isEmpty() && parseDouble(allData.get(minDeque.peekLast()).getClose()) >= curPrice)
                minDeque.pollLast();
            minDeque.offerLast(right);

            while (timestamps.get(left) < curTs - TWENTY_MINUTES_US) {
                if (maxDeque.peekFirst() == left) maxDeque.pollFirst();
                if (minDeque.peekFirst() == left) minDeque.pollFirst();
                left++;
            }

            if (right < 1200 || right - left < 60 || maxDeque.isEmpty() || minDeque.isEmpty()) continue;

            int maxIdx = maxDeque.peekFirst(), minIdx = minDeque.peekFirst();
            long maxTs = timestamps.get(maxIdx), minTs = timestamps.get(minIdx);
            double maxPrice = parseDouble(allData.get(maxIdx).getClose());
            double minPrice = parseDouble(allData.get(minIdx).getClose());

            // 趋势过滤
            boolean trendUp = false, trendDown = false;
            long trendStart = curTs - FIVE_MINUTES_US;
            int tIdx = Collections.binarySearch(timestamps, trendStart);
            if (tIdx < 0) tIdx = -tIdx - 1;
            if (tIdx < right) {
                double oldPrice = parseDouble(allData.get(tIdx).getClose());
                double change = (curPrice - oldPrice) / oldPrice;
                trendUp = change > TREND_THRESHOLD;
                trendDown = change < -TREND_THRESHOLD;
            }

            // 空单
            if (curTs - maxTs >= ONE_MINUTE_US && curTs - maxTs <= THREE_MINUTES_US
                    && curTs > maxTs && maxTs != lastShortTs) {
                if (!trendUp) {
                    BacktestTrade bt = buildBacktestTrade("空单", curTs, curPrice, maxPrice, minPrice,
                            maxTs, minTs, dataMap);
                    trades.add(bt);
                    lastShortTs = maxTs;
                    lastTradeTs = curTs;
                } else {
                    skippedByTrend++;
                    blockedReasons.put(curTs, "趋势过滤: 5分钟涨幅" + String.format("%.2f", (curPrice - parseDouble(allData.get(tIdx).getClose())) / parseDouble(allData.get(tIdx).getClose()) * 100) + "% > " + (TREND_THRESHOLD * 100) + "%");
                }
            } else if (curTs - maxTs >= ONE_MINUTE_US && curTs - maxTs <= THREE_MINUTES_US
                    && curTs > maxTs && maxTs == lastShortTs) {
                blockedReasons.put(curTs, "极值已用过(重复空单)");
            }

            // 多单
            if (curTs - minTs >= ONE_MINUTE_US && curTs - minTs <= THREE_MINUTES_US
                    && curTs > minTs && minTs != lastLongTs) {
                if (!trendDown) {
                    BacktestTrade bt = buildBacktestTrade("多单", curTs, curPrice, maxPrice, minPrice,
                            maxTs, minTs, dataMap);
                    trades.add(bt);
                    lastLongTs = minTs;
                    lastTradeTs = curTs;
                } else {
                    skippedByTrend++;
                    blockedReasons.put(curTs, "趋势过滤: 5分钟跌幅" + String.format("%.2f", Math.abs(curPrice - parseDouble(allData.get(tIdx).getClose())) / parseDouble(allData.get(tIdx).getClose()) * 100) + "% > " + (TREND_THRESHOLD * 100) + "%");
                }
            } else if (curTs - minTs >= ONE_MINUTE_US && curTs - minTs <= THREE_MINUTES_US
                    && curTs > minTs && minTs == lastLongTs) {
                blockedReasons.put(curTs, "极值已用过(重复多单)");
            }
        }

        System.out.println("  趋势过滤跳过: " + skippedByTrend);
        return trades;
    }

    /** 构建回测交易记录，计算10分钟后的结算价与盈亏 */
    private BacktestTrade buildBacktestTrade(String direction, long openTs, double openPrice,
                                             double maxPrice, double minPrice, long maxTs, long minTs,
                                             Map<Long, EthKlineSecond> dataMap) {
        BacktestTrade bt = new BacktestTrade();
        bt.direction = direction;
        bt.openTs = openTs;
        bt.openPrice = openPrice;
        bt.maxPrice = maxPrice;
        bt.minPrice = minPrice;
        bt.maxTs = maxTs;
        bt.minTs = minTs;

        long settleTs = openTs + TEN_MINUTES_US;
        EthKlineSecond settle = dataMap.get(settleTs);
        if (settle != null) {
            bt.settlePrice = parseDouble(settle.getClose());
            bt.settleTs = settleTs;
            bt.profit = "多单".equals(direction)
                    ? bt.settlePrice - openPrice
                    : openPrice - bt.settlePrice;
        }
        return bt;
    }

    /**
     * 逐笔对比实时交易与回测交易
     * 匹配规则：同方向 + 时间差 < 2秒
     * 对比维度：开单价、结算价、盈亏是否一致
     * 统计：匹配率、差异原因分布
     */
    private void compare(List<EthTradeRecord> realTrades, List<BacktestTrade> backtestTrades,
                          long rangeStart, long rangeEnd, boolean isMicro,
                          Map<Long, String> blockedReasons,
                          List<EthKlineSecond> allData, List<Long> timestamps,
                          Map<Long, EthKlineSecond> dataMap) {
        // 构建回测索引：openTs → BacktestTrade
        Map<Long, List<BacktestTrade>> btByTs = new HashMap<>();
        for (BacktestTrade bt : backtestTrades) {
            btByTs.computeIfAbsent(bt.openTs, k -> new ArrayList<>()).add(bt);
        }

        int matched = 0, diffPrice = 0, diffProfit = 0, diffSettle = 0;
        int rtOnly = 0;
        List<EthTradeRecord> unmatchedTrades = new ArrayList<>();
        double totalRtProfit = 0, totalBtProfit = 0;
        double totalRtProfitMatched = 0, totalBtProfitMatched = 0;
        Set<Long> matchedBtTs = new HashSet<>();

        // 打印表头
        System.out.println("序号  时间              方向  实时开单价  回测开单价  实时结算价  回测结算价  实时盈亏    回测盈亏    差异原因");
        System.out.println("────  ────────────────  ────  ──────────  ──────────  ──────────  ──────────  ──────────  ──────────  ────────");

        int seq = 0;
        for (EthTradeRecord rt : realTrades) {
            seq++;
            long rtOpenTs = rt.getOpenTimestamp();
            double rtOpenPrice = Double.parseDouble(rt.getOpenPrice());
            double rtProfit = rt.getProfit() != null ? Double.parseDouble(rt.getProfit()) : 0;
            double rtSettlePrice = rt.getClosePrice() != null ? Double.parseDouble(rt.getClosePrice()) : 0;
            totalRtProfit += rtProfit;

            // 查找匹配的回测交易（同方向，时间差<1秒）
            List<BacktestTrade> candidates = btByTs.getOrDefault(rtOpenTs, Collections.emptyList());
            BacktestTrade match = null;
            for (BacktestTrade bt : candidates) {
                if (bt.direction.equals(rt.getDirection()) && Math.abs(bt.openTs - rtOpenTs) < 1_000_000L) {
                    match = bt;
                    break;
                }
            }

            if (match == null) {
                // 尝试宽松匹配：时间差<2秒
                for (Map.Entry<Long, List<BacktestTrade>> e : btByTs.entrySet()) {
                    if (Math.abs(e.getKey() - rtOpenTs) < 2_000_000L) {
                        for (BacktestTrade bt : e.getValue()) {
                            if (bt.direction.equals(rt.getDirection())) {
                                match = bt;
                                break;
                            }
                        }
                        if (match != null) break;
                    }
                }
            }

            if (match != null) {
                matched++;
                matchedBtTs.add(match.openTs);
                totalBtProfitMatched += match.profit;

                List<String> reasons = new ArrayList<>();
                if (Math.abs(rtOpenPrice - match.openPrice) > 0.01) {
                    diffPrice++;
                    reasons.add("开单价差" + String.format("%.4f", Math.abs(rtOpenPrice - match.openPrice)));
                }
                if (Math.abs(rtSettlePrice - match.settlePrice) > 0.01 && rtSettlePrice > 0) {
                    diffSettle++;
                    reasons.add("结算价差" + String.format("%.4f", Math.abs(rtSettlePrice - match.settlePrice)));
                }
                if (Math.abs(rtProfit - match.profit) > 0.01) {
                    diffProfit++;
                    reasons.add("盈亏差" + String.format("%.4f", Math.abs(rtProfit - match.profit)));
                }

                String reason = reasons.isEmpty() ? "一致" : String.join(",", reasons);
                String icon = reasons.isEmpty() ? "✅" : "⚠️";

                System.out.println(String.format("%s %-4d %-16s %-4s %10.4f %10.4f %10.4f %10.4f %s %s %s",
                        icon, seq, formatTs(rtOpenTs, isMicro), rt.getDirection(),
                        rtOpenPrice, match.openPrice,
                        rtSettlePrice, match.settlePrice,
                        colorProfit(rtProfit), colorProfit(match.profit), reason));
            } else {
                rtOnly++;
                unmatchedTrades.add(rt);
                String diag = blockedReasons.getOrDefault(rtOpenTs, "无K线数据或极值条件不满足");
                System.out.println(String.format("❌ %-4d %-16s %-4s %10.4f %10s %10.4f %10s %s %8s %s",
                        seq, formatTs(rtOpenTs, isMicro), rt.getDirection(),
                        rtOpenPrice, "-", rtSettlePrice, "-",
                        colorProfit(rtProfit), "-", diag));
            }
        }

        // 汇总(回测范围内)
        double totalBtAll = 0;
        for (BacktestTrade bt : backtestTrades) totalBtAll += bt.profit;
        int btUnmatched = backtestTrades.size() - matched;

        // 汇总
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║              对比汇总                        ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 实时交易: %-4d 笔  回测交易(范围内): %-4d 笔  ║", realTrades.size(), backtestTrades.size()));
        System.out.println(String.format("║ 匹配成功: %-4d 笔  仅实时有: %-4d 笔      ║", matched, rtOnly));
        System.out.println(String.format("║ 回测有但未匹配: %-4d 笔                   ║", btUnmatched));
        System.out.println(String.format("║ 开单价不同: %-4d 笔  结算价不同: %-4d 笔 ║", diffPrice, diffSettle));
        System.out.println(String.format("║ 盈亏不同: %-4d 笔                         ║", diffProfit));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 实时总盈亏:     %+10.4f                  ║", totalRtProfit));
        System.out.println(String.format("║ 回测总盈亏(全部): %+10.4f                  ║", totalBtAll));
        System.out.println(String.format("║ 回测匹配盈亏:   %+10.4f                  ║", totalBtProfitMatched));
        System.out.println(String.format("║ 差异(匹配部分): %+10.4f                  ║", totalBtProfitMatched - totalRtProfitMatched));
        System.out.println("╚══════════════════════════════════════════════╝");

        if (!unmatchedTrades.isEmpty()) {
            diagnoseMissingTrades(unmatchedTrades, isMicro, allData, timestamps, dataMap);
        }
    }

    /**
     * 诊断"实时有但回测没有"的缺失交易
     * 检查每个缺失交易在回测中是否满足开单条件
     * 输出：窗口数据、极值年龄、趋势过滤、极值去重等诊断信息
     */
    private void diagnoseMissingTrades(List<EthTradeRecord> unmatchedTrades, boolean isMicro,
                                        List<EthKlineSecond> allData, List<Long> timestamps,
                                        Map<Long, EthKlineSecond> dataMap) {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║         缺失交易详细诊断                      ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        Set<Long> unmatchedTs = new HashSet<>();
        Map<Long, String> unmatchedDir = new HashMap<>();
        for (EthTradeRecord rt : unmatchedTrades) {
            unmatchedTs.add(rt.getOpenTimestamp());
            unmatchedDir.put(rt.getOpenTimestamp(), rt.getDirection());
        }

        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        int left = 0;
        long lastShortTs = -1, lastLongTs = -1, lastTradeTs = -1;

        for (int right = 0; right < allData.size(); right++) {
            EthKlineSecond cur = allData.get(right);
            long curTs = cur.getTimestamp();
            double curPrice = parseDouble(cur.getClose());

            while (!maxDeque.isEmpty() && parseDouble(allData.get(maxDeque.peekLast()).getClose()) <= curPrice)
                maxDeque.pollLast();
            maxDeque.offerLast(right);
            while (!minDeque.isEmpty() && parseDouble(allData.get(minDeque.peekLast()).getClose()) >= curPrice)
                minDeque.pollLast();
            minDeque.offerLast(right);

            while (timestamps.get(left) < curTs - TWENTY_MINUTES_US) {
                if (maxDeque.peekFirst() == left) maxDeque.pollFirst();
                if (minDeque.peekFirst() == left) minDeque.pollFirst();
                left++;
            }

            if (!unmatchedTs.contains(curTs)) continue;
            if (right < 1200 || right - left < 60 || maxDeque.isEmpty() || minDeque.isEmpty()) {
                System.out.println(String.format("\n🔍 %s %s:", formatTs(curTs, isMicro), unmatchedDir.get(curTs)));
                System.out.println("   ❌ 窗口数据不足 (right=" + right + ", window=" + (right - left) + ")");
                continue;
            }

            int maxIdx = maxDeque.peekFirst(), minIdx = minDeque.peekFirst();
            long maxTs = timestamps.get(maxIdx), minTs = timestamps.get(minIdx);
            double maxPrice = parseDouble(allData.get(maxIdx).getClose());
            double minPrice = parseDouble(allData.get(minIdx).getClose());

            boolean trendUp = false, trendDown = false;
            long trendStart = curTs - FIVE_MINUTES_US;
            int tIdx = Collections.binarySearch(timestamps, trendStart);
            if (tIdx < 0) tIdx = -tIdx - 1;
            double trendChange = 0;
            if (tIdx < right) {
                double oldPrice = parseDouble(allData.get(tIdx).getClose());
                trendChange = (curPrice - oldPrice) / oldPrice;
                trendUp = trendChange > TREND_THRESHOLD;
                trendDown = trendChange < -TREND_THRESHOLD;
            }

            String dir = unmatchedDir.get(curTs);
            System.out.println(String.format("\n🔍 %s %s:", formatTs(curTs, isMicro), dir));
            System.out.println(String.format("   当前价: %.4f  20分钟窗口: %s ~ %s",
                    curPrice, formatTs(timestamps.get(left), isMicro), formatTs(curTs, isMicro)));
            System.out.println(String.format("   窗口最高: %.4f (于 %s, 距今 %d秒)",
                    maxPrice, formatTs(maxTs, isMicro), (curTs - maxTs) / 1_000_000));
            System.out.println(String.format("   窗口最低: %.4f (于 %s, 距今 %d秒)",
                    minPrice, formatTs(minTs, isMicro), (curTs - minTs) / 1_000_000));
            System.out.println(String.format("   5分钟趋势: %+.2f%% (阈值: ±%.2f%%)",
                    trendChange * 100, TREND_THRESHOLD * 100));
            System.out.println(String.format("   距上次开单: %s",
                    lastTradeTs == -1 ? "无" : (curTs - lastTradeTs) / 1_000_000 + "秒"));

            if ("空单".equals(dir)) {
                long age = (curTs - maxTs) / 1_000_000;
                System.out.print(String.format("   空单条件: 极值年龄=%ds(需1~3分) ", age));
                if (age < 60 || age > 180) System.out.println("❌ 年龄不符合");
                else if (maxTs == lastShortTs) System.out.println("❌ 极值已用过");
                else if (trendUp) System.out.println(String.format("❌ 趋势过滤(涨幅%.2f%%)", trendChange * 100));
                else System.out.println("✅ 应开单(但数据不一致)");
            } else {
                long age = (curTs - minTs) / 1_000_000;
                System.out.print(String.format("   多单条件: 极值年龄=%ds(需1~3分) ", age));
                if (age < 60 || age > 180) System.out.println("❌ 年龄不符合");
                else if (minTs == lastLongTs) System.out.println("❌ 极值已用过");
                else if (trendDown) System.out.println(String.format("❌ 趋势过滤(跌幅%.2f%%)", Math.abs(trendChange) * 100));
                else System.out.println("✅ 应开单(但数据不一致)");
            }
        }
    }

}