package com.alphay.boot.web.test;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.mapper.EthKlineSecondMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 币安事件合约策略 - 抽象基类
 * 提供数据加载、回测引擎、结果统计、打印等公共能力
 * 具体策略只需要覆盖抽象方法配置参数即可
 *
 * 策略核心逻辑：
 * 1. 在滑动窗口中找出极值（最高价/最低价）
 * 2. 等待1~3分钟（确认拐点）
 * 3. 根据趋势方向判断是否开单（顺趋势/逆趋势）
 * 4. 到期后判断价格方向是否预测正确
 *
 * @author d3code
 */
public abstract class EventContractStrategyBase {

    /** 数据库访问：K线数据Mapper */
    protected EthKlineSecondMapper ethKlineSecondMapper;

    /** ANSI颜色：绿色（盈利/正确）*/
    protected static final String GREEN = "\033[32m";
    /** ANSI颜色：红色（亏损/错误）*/
    protected static final String RED = "\033[31m";
    /** ANSI颜色：重置 */
    protected static final String RESET = "\033[0m";

    // ==================== 策略参数（子类必须实现）====================

    /** 获取策略名称，用于结果打印 */
    protected abstract String getStrategyName();

    /** 趋势确认窗口大小（分钟），判断当前趋势方向用 */
    protected abstract int getTrendMinutes();

    /** 趋势强度阈值，超过这个阈值才认为是有效趋势 */
    protected abstract double getTrendThreshold();

    /** 极值搜索窗口大小（分钟），在这个窗口内找极值 */
    protected abstract int getExtremeWindowMinutes();

    /** 是否只做顺趋势单：true=只做顺趋势，false=顺逆都做 */
    protected abstract boolean isOnlyTrendAligned();

    /** 是否跳过横盘：true=横盘（趋势强度不够）不开单，false=横盘也开单 */
    protected abstract boolean isSkipSideways();

    // ==================== 数据模型 ====================

    /** 到期时间类型：10分钟、30分钟、1小时 */
    protected enum ExpiryType {
        T_10MIN(10, "10分钟"),
        T_30MIN(30, "30分钟"),
        T_60MIN(60, "1小时");

        final int minutes;
        final String label;
        ExpiryType(int m, String l) { minutes = m; label = l; }
    }

    /** 回测结果统计 */
    protected static class BacktestResult {
        int total = 0;             // 总信号数
        int correct = 0;           // 预测正确数
        int wrong = 0;             // 预测错误数
        double winRate;            // 胜率
        int longCorrect = 0, longWrong = 0;       // 多单统计
        int shortCorrect = 0, shortWrong = 0;     // 空单统计
        int trendAlignedCorrect = 0, trendAlignedWrong = 0;  // 顺趋势统计
        int trendAgainstCorrect = 0, trendAgainstWrong = 0;  // 逆趋势统计
    }

    /** 单个交易信号 */
    protected static class TradeSignal {
        long openTs;               // 开单时间戳（微秒）
        String direction;          // 方向：多单/空单
        double openPrice;          // 开单价
        double closePrice;         // 到期收盘价
        boolean correct;           // 是否预测正确
        double trendChange;        // 开单前趋势变化率
        int expiryMinutes;         // 到期时间（分钟）
    }

    // ==================== 工具方法 ====================

    /** 给盈亏上色：绿色盈利，红色亏损 */
    protected String colorProfit(double p) {
        String c = p > 0 ? GREEN : p < 0 ? RED : "";
        String r = p > 0 || p < 0 ? RESET : "";
        return String.format("%s%+8.4f%s", c, p, r);
    }

    /** 给结果上色：绿色√正确，红色×错误 */
    protected String colorWin(boolean win) {
        return win ? GREEN + "√" + RESET : RED + "×" + RESET;
    }

    /** 格式化时间戳显示，兼容毫秒和微秒 */
    protected String formatTs(long ts, boolean isMicro) {
        long ms = isMicro ? ts / 1000 : ts;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }

    /** 字符串转Double */
    protected double parseDouble(String s) {
        return Double.parseDouble(s);
    }

    // ==================== 回测引擎入口 ====================

    /** 主入口：加载数据，对每个到期时间执行回测 */
    protected void run() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║   事件合约策略回测: " + padRight(getStrategyName(), 27) + "║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("📋 趋势窗口: " + getTrendMinutes() + "分钟 | 阈值: "
                + (getTrendThreshold() * 100) + "% | 极值窗口: " + getExtremeWindowMinutes() + "分钟");
        System.out.println("   只做顺趋势: " + isOnlyTrendAligned()
                + " | 横盘不开: " + isSkipSideways());
        System.out.println();

        // 从数据库加载所有K线数据
        List<EthKlineSecond> allData = ethKlineSecondMapper.selectList(new LambdaQueryWrapper<>());
        // 按时间戳排序
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));
        if (allData.isEmpty()) { System.out.println("⚠️ 没有数据"); return; }

        // 判断时间戳单位是否为微秒
        boolean isMicro = allData.get(0).getTimestamp() > 1000000000000000L;
        System.out.println("📊 数据: " + formatTs(allData.get(0).getTimestamp(), isMicro)
                + " ~ " + formatTs(allData.get(allData.size() - 1).getTimestamp(), isMicro)
                + "  共 " + allData.size() + " 条");
        System.out.println();

        // 构建时间戳映射表，方便O(1)查找指定时间点的K线
        Map<Long, EthKlineSecond> dataMap = new HashMap<>(allData.size());
        List<Long> timestamps = new ArrayList<>(allData.size());
        for (EthKlineSecond d : allData) {
            dataMap.put(d.getTimestamp(), d);
            timestamps.add(d.getTimestamp());
        }

        // 分别对10分钟、30分钟、1小时到期执行回测
        for (ExpiryType expiry : ExpiryType.values()) {
            backtestForExpiry(expiry, allData, timestamps, dataMap, isMicro);
        }
    }

    /**
     * 对指定到期时间执行回测
     * 使用滑动窗口 + 单调队列维护极值，O(n) 复杂度遍历所有时间点
     *
     * @param expiry      到期时间类型（10/30/60分钟）
     * @param allData     所有K线数据（已排序）
     * @param timestamps  所有时间戳列表（用于二分查找）
     * @param dataMap     时间戳到K线数据的映射
     * @param isMicro     时间戳是否微秒
     */
    private void backtestForExpiry(ExpiryType expiry, List<EthKlineSecond> allData,
                                   List<Long> timestamps, Map<Long, EthKlineSecond> dataMap,
                                   boolean isMicro) {
        System.out.println("══════════════════════════════════════════════════");
        System.out.println("📌 到期: " + expiry.minutes + "分钟 (" + expiry.label + ")");
        System.out.println();

        BacktestResult result = new BacktestResult();
        List<TradeSignal> wrongSignals = new ArrayList<>();

        // 将分钟转换为微秒
        long trendUs = getTrendMinutes() * 60 * 1_000_000L;       // 趋势窗口
        long extremeUs = getExtremeWindowMinutes() * 60 * 1_000_000L; // 极值窗口
        long expiryUs = expiry.minutes * 60 * 1_000_000L;         // 到期时间

        // 滑动窗口左指针
        int leftExtreme = 0;
        // 单调队列：维护窗口内的最大值和最小值索引
        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        // 极值去重标记：记录已使用的极值时间戳，避免同一极值重复开单
        long lastMaxTs = -1, lastMinTs = -1;

        // 遍历每个时间点作为"当前时间"
        for (int right = 0; right < allData.size(); right++) {
            EthKlineSecond cur = allData.get(right);
            long curTs = cur.getTimestamp();
            double curPrice = parseDouble(cur.getClose());

            // 维护最大值单调递减队列
            while (!maxDeque.isEmpty() && parseDouble(allData.get(maxDeque.peekLast()).getClose()) <= curPrice)
                maxDeque.pollLast();
            maxDeque.offerLast(right);
            // 维护最小值单调递增队列
            while (!minDeque.isEmpty() && parseDouble(allData.get(minDeque.peekLast()).getClose()) >= curPrice)
                minDeque.pollLast();
            minDeque.offerLast(right);

            // 移动左指针，移除超出窗口的数据
            while (timestamps.get(leftExtreme) < curTs - extremeUs) {
                if (!maxDeque.isEmpty() && maxDeque.peekFirst() == leftExtreme) maxDeque.pollFirst();
                if (!minDeque.isEmpty() && minDeque.peekFirst() == leftExtreme) minDeque.pollFirst();
                leftExtreme++;
            }

            // 前期跳过：需要足够多的历史数据
            if (right < getTrendMinutes() * 60) continue;
            if (right - leftExtreme < getExtremeWindowMinutes() * 60) continue;
            // 必须有到期时间对应的K线数据
            if (!dataMap.containsKey(curTs + expiryUs)) continue;
            if (maxDeque.isEmpty() || minDeque.isEmpty()) continue;

            // 获取窗口内极值
            int maxIdx = maxDeque.peekFirst(), minIdx = minDeque.peekFirst();
            long maxTs = timestamps.get(maxIdx), minTs = timestamps.get(minIdx);

            // 计算趋势（从当前时间往回看N分钟的价格变化率）
            long trendStartTs = curTs - trendUs;
            int tIdx = Collections.binarySearch(timestamps, trendStartTs);
            if (tIdx < 0) tIdx = -tIdx - 1; // 二分查找未命中时取插入点
            if (tIdx >= right) continue;
            double oldPrice = parseDouble(allData.get(tIdx).getClose());
            double trendChange = (curPrice - oldPrice) / oldPrice;
            boolean trendUp = trendChange > getTrendThreshold();      // 上升趋势
            boolean trendDown = trendChange < -getTrendThreshold();   // 下降趋势

            // 横盘过滤：趋势强度不够时跳过
            if (isSkipSideways() && !trendUp && !trendDown) continue;

            // ===== 多单开仓条件 =====
            // 条件：最低价出现在1~3分钟前，且该极值未被使用过
            if (curTs - minTs >= 60_000_000L && curTs - minTs <= 180_000_000L && minTs != lastMinTs) {
                // 顺趋势模式：下跌趋势中不开多单
                boolean canOpen = !isOnlyTrendAligned() || !trendDown;
                if (canOpen) {
                    lastMinTs = minTs; // 标记该极值已使用
                    EthKlineSecond ek = dataMap.get(curTs + expiryUs);
                    if (ek != null) {
                        // 判断预测正确：到期价格 > 开单价格
                        boolean correct = parseDouble(ek.getClose()) > curPrice;
                        recordTrade(result, wrongSignals, curTs, "多单", curPrice,
                                parseDouble(ek.getClose()), correct, trendChange, expiry.minutes, trendUp);
                    }
                }
            }

            // ===== 空单开仓条件 =====
            // 条件：最高价出现在1~3分钟前，且该极值未被使用过
            if (curTs - maxTs >= 60_000_000L && curTs - maxTs <= 180_000_000L && maxTs != lastMaxTs) {
                // 顺趋势模式：上升趋势中不做空单
                boolean canOpen = !isOnlyTrendAligned() || !trendUp;
                if (canOpen) {
                    lastMaxTs = maxTs; // 标记该极值已使用
                    EthKlineSecond ek = dataMap.get(curTs + expiryUs);
                    if (ek != null) {
                        // 判断预测正确：到期价格 < 开单价格
                        boolean correct = parseDouble(ek.getClose()) < curPrice;
                        recordTrade(result, wrongSignals, curTs, "空单", curPrice,
                                parseDouble(ek.getClose()), correct, trendChange, expiry.minutes, trendDown);
                    }
                }
            }
        }

        // 计算胜率并打印结果
        result.winRate = result.total > 0 ? (double) result.correct / result.total * 100 : 0;
        printResult(result, wrongSignals, isMicro);
    }

    /**
     * 记录单笔交易信号并更新统计
     *
     * @param r           结果统计对象
     * @param wrongSignals 错误信号列表（用于后续打印）
     * @param ts          开单时间戳
     * @param dir         方向（多单/空单）
     * @param openP       开单价格
     * @param closeP      到期收盘价
     * @param correct     是否预测正确
     * @param trendChg    趋势变化率
     * @param expiryMin   到期分钟数
     * @param isAligned   是否顺趋势
     */
    private void recordTrade(BacktestResult r, List<TradeSignal> wrongSignals,
                             long ts, String dir, double openP, double closeP,
                             boolean correct, double trendChg, int expiryMin, boolean isAligned) {
        TradeSignal s = new TradeSignal();
        s.openTs = ts; s.direction = dir; s.openPrice = openP; s.closePrice = closeP;
        s.correct = correct; s.trendChange = trendChg; s.expiryMinutes = expiryMin;

        r.total++;
        if (correct) {
            r.correct++;
            if (isAligned) r.trendAlignedCorrect++; else r.trendAgainstCorrect++;
            if ("多单".equals(dir)) r.longCorrect++; else r.shortCorrect++;
        } else {
            r.wrong++;
            wrongSignals.add(s);
            if (isAligned) r.trendAlignedWrong++; else r.trendAgainstWrong++;
            if ("多单".equals(dir)) r.longWrong++; else r.shortWrong++;
        }
    }

    /**
     * 打印回测结果统计
     * 包含：总信号、胜率、多空分别胜率、顺趋势胜率
     * 以及最近10个错误信号的详细信息
     */
    private void printResult(BacktestResult r, List<TradeSignal> wrongSignals, boolean isMicro) {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║              回测结果                         ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println(String.format("║ 信号: %-4d  正确: %-4d  错误: %-4d  胜率: %5.1f%% ║",
                r.total, r.correct, r.wrong, r.winRate));
        System.out.println(String.format("║ 多: %-2d对/%-2d错=%.1f%%  空: %-2d对/%-2d错=%.1f%%     ║",
                r.longCorrect, r.longWrong,
                (r.longCorrect + r.longWrong) > 0 ? 100.0 * r.longCorrect / (r.longCorrect + r.longWrong) : 0,
                r.shortCorrect, r.shortWrong,
                (r.shortCorrect + r.shortWrong) > 0 ? 100.0 * r.shortCorrect / (r.shortCorrect + r.shortWrong) : 0));
        System.out.println(String.format("║ 顺趋势: %-2d对/%-2d错=%.1f%%                         ║",
                r.trendAlignedCorrect, r.trendAlignedWrong,
                (r.trendAlignedCorrect + r.trendAlignedWrong) > 0
                        ? 100.0 * r.trendAlignedCorrect / (r.trendAlignedCorrect + r.trendAlignedWrong) : 0));
        System.out.println("╚══════════════════════════════════════════════╝");

        // 打印最近10个错误信号用于分析
        if (!wrongSignals.isEmpty()) {
            System.out.println("\n📋 最近" + Math.min(10, wrongSignals.size()) + "个错误信号:");
            int count = 0;
            for (int i = wrongSignals.size() - 1; i >= 0 && count < 10; i--, count++) {
                TradeSignal s = wrongSignals.get(i);
                System.out.println(String.format(" %2d  %s  %s  %.2f→%.2f  %+5.2f%%  %s",
                        count + 1, formatTs(s.openTs, isMicro), s.direction.substring(0, 2),
                        s.openPrice, s.closePrice, s.trendChange * 100, colorWin(s.correct)));
            }
        }
        System.out.println();
    }

    /** 右侧填充空格，用于格式化输出对齐 */
    private String padRight(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }
}