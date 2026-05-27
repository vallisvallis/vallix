// 替换为：
package com.alphay.boot.web.controller.vallix;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@Slf4j
public class BackTestService {

    @Autowired
    private VallisUsdtEventTool vallisUsdtEventTool;

    @Autowired
    private KlineCombineService combineService;

    /**
     * 策略A回测：10分钟均值回归策略
     */
    public BackTestResult backtestStrategyA(int dataPoints) {
        log.info("==================== 开始策略A回测（10分钟均值回归） ====================");

        // 1. 获取数据
        List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(dataPoints);
        List<Vallisusdt> rolling10MinList = combineService.calculateRolling10Min(oneMinList);

        if (rolling10MinList.size() < 100) {
            log.error("回测数据不足，至少需要100个10分钟窗口");
            return new BackTestResult("策略A", 0, 0, 0, 0, 0, 0, 0);
        }

        // 2. 策略参数
        double entryThreshold = KlineStrategyUtil.Q75_THRESHOLD;    // 入场阈值 0.056%
        double stopLossThreshold = KlineStrategyUtil.Q95_THRESHOLD; // 止损阈值 0.198%
        double targetReturn = KlineStrategyUtil.TARGET_RETURN;      // 目标收益 0.005%

        // 3. 执行回测
        List<TradeRecord> trades = new ArrayList<>();
        double totalProfit = 0.0;
        double maxDrawdown = 0.0;
        double peak = 0.0;
        double currentEquity = 10000.0; // 初始资金 $10,000
        List<Double> equityCurve = new ArrayList<>();
        equityCurve.add(currentEquity);

        for (int i = 0; i < rolling10MinList.size(); i++) {
            Vallisusdt kline = rolling10MinList.get(i);
            double change = KlineStrategyUtil.getChangePercent(kline);
            double absChange = Math.abs(change);

            // 入场信号：波动超过阈值
            if (absChange > entryThreshold) {
                TradeRecord trade = new TradeRecord();
                trade.entryTime = kline.getEndTime();
                trade.entryPrice = new BigDecimal(kline.getEndPrice());
                trade.direction = change > 0 ? -1 : 1; // 反向交易
                trade.entryChange = change;

                // 计算止损和止盈
                BigDecimal entryPrice = trade.entryPrice;
                double stopLossPct = stopLossThreshold / 100.0;
                double targetPct = targetReturn / 100.0;

                if (trade.direction == 1) { // 做多
                    trade.stopLoss = entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(stopLossPct)));
                    trade.takeProfit = entryPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(targetPct)));
                } else { // 做空
                    trade.stopLoss = entryPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(stopLossPct)));
                    trade.takeProfit = entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(targetPct)));
                }

                // 模拟平仓（假设持仓10分钟）
                int holdPeriods = Math.min(10, rolling10MinList.size() - i - 1);
                if (holdPeriods > 0) {
                    Vallisusdt exitKline = rolling10MinList.get(i + holdPeriods);
                    trade.exitTime = exitKline.getEndTime();
                    trade.exitPrice = new BigDecimal(exitKline.getEndPrice());

                    // 计算盈亏
                    if (trade.direction == 1) { // 做多
                        trade.profitPct = trade.exitPrice.subtract(trade.entryPrice)
                                .divide(trade.entryPrice, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .doubleValue();
                    } else { // 做空
                        trade.profitPct = trade.entryPrice.subtract(trade.exitPrice)
                                .divide(trade.entryPrice, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .doubleValue();
                    }

                    // 检查是否触发止损
                    if ((trade.direction == 1 && trade.exitPrice.compareTo(trade.stopLoss) <= 0) ||
                            (trade.direction == -1 && trade.exitPrice.compareTo(trade.stopLoss) >= 0)) {
                        trade.stopLossTriggered = true;
                        // 按止损价计算盈亏
                        if (trade.direction == 1) {
                            trade.profitPct = trade.stopLoss.subtract(trade.entryPrice)
                                    .divide(trade.entryPrice, 6, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .doubleValue();
                        } else {
                            trade.profitPct = trade.entryPrice.subtract(trade.stopLoss)
                                    .divide(trade.entryPrice, 6, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .doubleValue();
                        }
                    }

                    trades.add(trade);
                    totalProfit += trade.profitPct;

                    // 更新资金曲线
                    double positionSize = 0.02; // 2%仓位
                    double profitAmount = currentEquity * positionSize * (trade.profitPct / 100.0);
                    currentEquity += profitAmount;
                    equityCurve.add(currentEquity);

                    // 计算最大回撤
                    if (currentEquity > peak) {
                        peak = currentEquity;
                    }
                    double drawdown = (peak - currentEquity) / peak * 100;
                    if (drawdown > maxDrawdown) {
                        maxDrawdown = drawdown;
                    }

                    // 跳过持仓期间
                    i += holdPeriods;
                }
            }
        }

        // 4. 计算统计指标
        int totalTrades = trades.size();
        long winningTrades = trades.stream().filter(t -> t.profitPct > 0).count();
        long losingTrades = trades.stream().filter(t -> t.profitPct < 0).count();
        double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;

        double avgWin = trades.stream().filter(t -> t.profitPct > 0)
                .mapToDouble(t -> t.profitPct)
                .average().orElse(0.0);
        double avgLoss = trades.stream().filter(t -> t.profitPct < 0)
                .mapToDouble(t -> Math.abs(t.profitPct))
                .average().orElse(0.0);
        double profitLossRatio = avgLoss > 0 ? avgWin / avgLoss : Double.POSITIVE_INFINITY;

        // 夏普比率（简化版）
        double avgReturn = totalProfit / totalTrades;
        double stdReturn = calculateStdDev(trades.stream()
                .mapToDouble(t -> t.profitPct)
                .toArray());
        double sharpeRatio = stdReturn > 0 ? avgReturn / stdReturn * Math.sqrt(252) : 0;

        // 5. 输出结果
        log.info("策略A回测完成:");
        log.info("总交易次数: {}", totalTrades);
        log.info("盈利次数: {} (胜率: {}%)", winningTrades, String.format("%.2f", winRate));
        log.info("亏损次数: {}", losingTrades);
        log.info("平均盈利: {}%", String.format("%.4f", avgWin));
        log.info("平均亏损: {}%", String.format("%.4f", avgLoss));
        log.info("盈亏比: {}", String.format("%.2f", profitLossRatio));
        log.info("总收益: {}%", String.format("%.4f", totalProfit));
        log.info("最大回撤: {}%", String.format("%.2f", maxDrawdown));
        log.info("夏普比率: {}", String.format("%.2f", sharpeRatio));
        log.info("最终资金: ${}", String.format("%.2f", currentEquity));
        log.info("======================================================");

        return new BackTestResult(
                "策略A（10分钟均值回归）",
                totalTrades, winningTrades, winRate,
                totalProfit, maxDrawdown, sharpeRatio,
                profitLossRatio
        );
    }

    /**
     * 策略B回测：30分钟趋势突破策略
     */
    public BackTestResult backtestStrategyB(int dataPoints) {
        log.info("==================== 开始策略B回测（30分钟趋势突破） ====================");

        List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(dataPoints);
        List<Vallisusdt> rolling30MinList = combineService.calculateRolling30Min(oneMinList);

        if (rolling30MinList.size() < 50) {
            log.error("回测数据不足，至少需要50个30分钟窗口");
            return new BackTestResult("策略B", 0, 0, 0, 0, 0, 0, 0);
        }

        // 策略参数
        double stopLossPct = KlineStrategyUtil.MAX_LOSS;    // 止损 0.3%
        double takeProfitPct = KlineStrategyUtil.MAX_PROFIT; // 止盈 0.6%

        List<TradeRecord> trades = new ArrayList<>();
        double totalProfit = 0.0;
        double maxDrawdown = 0.0;
        double peak = 0.0;
        double currentEquity = 10000.0;
        List<Double> equityCurve = new ArrayList<>();
        equityCurve.add(currentEquity);

        for (int i = 1; i < rolling30MinList.size(); i++) {
            Vallisusdt current = rolling30MinList.get(i);
            Vallisusdt previous = rolling30MinList.get(i - 1);

            // 突破信号
            int signal = KlineStrategyUtil.get30MinSignal(current, previous);
            if (signal != 0) {
                TradeRecord trade = new TradeRecord();
                trade.entryTime = current.getEndTime();
                trade.entryPrice = new BigDecimal(current.getEndPrice());
                trade.direction = signal;

                // 设置止损止盈
                BigDecimal entryPrice = trade.entryPrice;
                double sl = stopLossPct / 100.0;
                double tp = takeProfitPct / 100.0;

                if (trade.direction == 1) { // 做多
                    trade.stopLoss = entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(sl)));
                    trade.takeProfit = entryPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(tp)));
                } else { // 做空
                    trade.stopLoss = entryPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(sl)));
                    trade.takeProfit = entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(tp)));
                }

                // 模拟平仓（持仓30-90分钟）
                int holdPeriods = Math.min(3, rolling30MinList.size() - i - 1);
                if (holdPeriods > 0) {
                    Vallisusdt exitKline = rolling30MinList.get(i + holdPeriods);
                    trade.exitTime = exitKline.getEndTime();
                    trade.exitPrice = new BigDecimal(exitKline.getEndPrice());

                    // 检查是否触发止损/止盈
                    boolean stopLossHit = false;
                    boolean takeProfitHit = false;

                    if (trade.direction == 1) { // 做多
                        stopLossHit = trade.exitPrice.compareTo(trade.stopLoss) <= 0;
                        takeProfitHit = trade.exitPrice.compareTo(trade.takeProfit) >= 0;
                    } else { // 做空
                        stopLossHit = trade.exitPrice.compareTo(trade.stopLoss) >= 0;
                        takeProfitHit = trade.exitPrice.compareTo(trade.takeProfit) <= 0;
                    }

                    // 计算盈亏
                    if (trade.direction == 1) {
                        trade.profitPct = trade.exitPrice.subtract(trade.entryPrice)
                                .divide(trade.entryPrice, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .doubleValue();
                    } else {
                        trade.profitPct = trade.entryPrice.subtract(trade.exitPrice)
                                .divide(trade.entryPrice, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .doubleValue();
                    }

                    // 如果触发止损，按止损价计算
                    if (stopLossHit) {
                        trade.stopLossTriggered = true;
                        if (trade.direction == 1) {
                            trade.profitPct = trade.stopLoss.subtract(trade.entryPrice)
                                    .divide(trade.entryPrice, 6, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .doubleValue();
                        } else {
                            trade.profitPct = trade.entryPrice.subtract(trade.stopLoss)
                                    .divide(trade.entryPrice, 6, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .doubleValue();
                        }
                    }

                    trades.add(trade);
                    totalProfit += trade.profitPct;

                    // 更新资金曲线
                    double positionSize = 0.015; // 1.5%仓位
                    double profitAmount = currentEquity * positionSize * (trade.profitPct / 100.0);
                    currentEquity += profitAmount;
                    equityCurve.add(currentEquity);

                    // 计算最大回撤
                    if (currentEquity > peak) {
                        peak = currentEquity;
                    }
                    double drawdown = (peak - currentEquity) / peak * 100;
                    if (drawdown > maxDrawdown) {
                        maxDrawdown = drawdown;
                    }

                    i += holdPeriods;
                }
            }
        }

        // 计算统计指标
        int totalTrades = trades.size();
        long winningTrades = trades.stream().filter(t -> t.profitPct > 0).count();
        double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;

        double avgWin = trades.stream().filter(t -> t.profitPct > 0)
                .mapToDouble(t -> t.profitPct)
                .average().orElse(0.0);
        double avgLoss = trades.stream().filter(t -> t.profitPct < 0)
                .mapToDouble(t -> Math.abs(t.profitPct))
                .average().orElse(0.0);
        double profitLossRatio = avgLoss > 0 ? avgWin / avgLoss : Double.POSITIVE_INFINITY;

        double avgReturn = totalTrades > 0 ? totalProfit / totalTrades : 0;
        double stdReturn = calculateStdDev(trades.stream()
                .mapToDouble(t -> t.profitPct)
                .toArray());
        double sharpeRatio = stdReturn > 0 ? avgReturn / stdReturn * Math.sqrt(252) : 0;

        log.info("策略B回测完成:");
        log.info("总交易次数: {}", totalTrades);
        log.info("盈利次数: {} (胜率: {}%)", winningTrades, String.format("%.2f", winRate));
        log.info("总收益: {}%", String.format("%.4f", totalProfit));
        log.info("最大回撤: {}%", String.format("%.2f", maxDrawdown));
        log.info("夏普比率: {}", String.format("%.2f", sharpeRatio));
        log.info("盈亏比: {}", String.format("%.2f", profitLossRatio));
        log.info("======================================================");

        return new BackTestResult(
                "策略B（30分钟趋势突破）",
                totalTrades, winningTrades, winRate,
                totalProfit, maxDrawdown, sharpeRatio,
                profitLossRatio
        );
    }

    /**
     * 综合回测（两个策略一起跑）
     */
    public Map<String, Object> comprehensiveBackTest(int dataPoints) {
        Map<String, Object> result = new LinkedHashMap<>();

        BackTestResult strategyA = backtestStrategyA(dataPoints);
        BackTestResult strategyB = backtestStrategyB(dataPoints);

        result.put("strategyA", strategyA.toMap());
        result.put("strategyB", strategyB.toMap());

        // 比较两个策略 - 修复Map.of()不兼容Java 8的问题
        Map<String, String> comparison = new HashMap<>();
        comparison.put("better_win_rate", strategyA.winRate > strategyB.winRate ? "策略A" : "策略B");
        comparison.put("better_sharpe", strategyA.sharpeRatio > strategyB.sharpeRatio ? "策略A" : "策略B");
        comparison.put("lower_drawdown", strategyA.maxDrawdown < strategyB.maxDrawdown ? "策略A" : "策略B");
        result.put("comparison", comparison);

        return result;
    }

    /**
     * 手动触发回测（兼容原有接口）
     */
    public BackTestResult startBackTest() {
        return backtestStrategyA(2000);
    }

    private double calculateStdDev(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0.0);
        double variance = Arrays.stream(values)
                .map(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
        return Math.sqrt(variance);
    }

    /**
     * 交易记录
     */
    private static class TradeRecord {
        Date entryTime;
        Date exitTime;
        BigDecimal entryPrice;
        BigDecimal exitPrice;
        BigDecimal stopLoss;
        BigDecimal takeProfit;
        int direction; // 1=做多, -1=做空
        double entryChange;
        double profitPct;
        boolean stopLossTriggered;
    }

    /**
     * 回测结果实体
     */
    public static class BackTestResult {
        private String strategyName;
        private int totalTrades;
        private long winningTrades;
        private double winRate;
        private double totalProfit;
        private double maxDrawdown;
        private double sharpeRatio;
        private double profitLossRatio;

        public BackTestResult(String strategyName, int totalTrades, long winningTrades,
                              double winRate, double totalProfit, double maxDrawdown,
                              double sharpeRatio, double profitLossRatio) {
            this.strategyName = strategyName;
            this.totalTrades = totalTrades;
            this.winningTrades = winningTrades;
            this.winRate = winRate;
            this.totalProfit = totalProfit;
            this.maxDrawdown = maxDrawdown;
            this.sharpeRatio = sharpeRatio;
            this.profitLossRatio = profitLossRatio;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("strategy_name", strategyName);
            map.put("total_trades", totalTrades);
            map.put("winning_trades", winningTrades);
            map.put("win_rate", String.format("%.2f%%", winRate));
            map.put("total_profit", String.format("%.4f%%", totalProfit));
            map.put("max_drawdown", String.format("%.2f%%", maxDrawdown));
            map.put("sharpe_ratio", String.format("%.2f", sharpeRatio));
            map.put("profit_loss_ratio", String.format("%.2f", profitLossRatio));
            return map;
        }

        // Getters
        public String getStrategyName() { return strategyName; }
        public int getTotalTrades() { return totalTrades; }
        public long getWinningTrades() { return winningTrades; }
        public double getWinRate() { return winRate; }
        public double getTotalProfit() { return totalProfit; }
        public double getMaxDrawdown() { return maxDrawdown; }
        public double getSharpeRatio() { return sharpeRatio; }
        public double getProfitLossRatio() { return profitLossRatio; }
    }
}