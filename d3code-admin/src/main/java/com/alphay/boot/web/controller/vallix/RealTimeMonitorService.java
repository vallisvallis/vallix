package com.alphay.boot.web.controller.vallix;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@EnableScheduling
public class RealTimeMonitorService {
    @Autowired
    private VallisUsdtEventTool vallisUsdtEventTool;

    @Autowired
    private KlineCombineService combineService;

    // 监控数据缓存
    private Map<String, Object> latestData = new LinkedHashMap<>();
    private List<Map<String, Object>> signalHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 100;

    // 交易状态
    private boolean inPosition = false;
    private Map<String, Object> currentPosition = null;
    private List<Map<String, Object>> tradeHistory = new ArrayList<>();

    /**
     * 主监控任务（每10秒执行一次）
     */
    // @Scheduled(fixedRate = 10000)  // 已禁用该定时任务
    public void monitorTask() {
        try {
            // 1. 获取实时数据
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(200);
            if (oneMinList.size() < 30) {
                log.warn("数据量不足，跳过本次监控");
                return;
            }

            // 2. 计算滚动窗口
            List<Vallisusdt> rolling10MinList = combineService.calculateRolling10Min(oneMinList);
            List<Vallisusdt> rolling30MinList = combineService.calculateRolling30Min(oneMinList);

            if (rolling10MinList.isEmpty() || rolling30MinList.isEmpty()) {
                return;
            }

            // 3. 获取最新K线
            Vallisusdt latest10Min = rolling10MinList.get(rolling10MinList.size() - 1);
            Vallisusdt latest30Min = rolling30MinList.get(rolling30MinList.size() - 1);

            // 4. 计算指标
            double change10 = KlineStrategyUtil.getChangePercent(latest10Min);
            double change30 = KlineStrategyUtil.getChangePercent(latest30Min);
            int continuousCount = KlineStrategyUtil.countContinuous(rolling10MinList);

            // 5. 生成交易信号
            Map<String, Object> signals = generateSignals(rolling10MinList, rolling30MinList);

            // 6. 检查持仓状态
            if (inPosition) {
                checkPositionStatus(latest10Min, latest30Min);
            }

            // 7. 更新监控数据
            updateMonitorData(oneMinList, rolling10MinList, rolling30MinList,
                    latest10Min, latest30Min, change10, change30,
                    continuousCount, signals);

            // 8. 记录信号历史
            recordSignalHistory(signals);

            // 9. 日志输出
            logMonitorInfo(change10, change30, continuousCount, signals);

        } catch (Exception e) {
            log.error("监控任务异常", e);
        }
    }

    /**
     * 生成交易信号
     */
    private Map<String, Object> generateSignals(List<Vallisusdt> rolling10MinList,
                                                List<Vallisusdt> rolling30MinList) {
        Map<String, Object> signals = new LinkedHashMap<>();

        if (rolling10MinList.size() < 2 || rolling30MinList.size() < 2) {
            return signals;
        }

        // 获取最新K线
        Vallisusdt latest10 = rolling10MinList.get(rolling10MinList.size() - 1);
        Vallisusdt prev10 = rolling10MinList.get(rolling10MinList.size() - 2);
        Vallisusdt latest30 = rolling30MinList.get(rolling30MinList.size() - 1);
        Vallisusdt prev30 = rolling30MinList.get(rolling30MinList.size() - 2);

        // 10分钟策略信号
        int signal10 = KlineStrategyUtil.get10MinSignal(latest10);
        String signal10Desc = KlineStrategyUtil.get10MinSignalDesc(signal10);

        // 30分钟策略信号
        int signal30 = KlineStrategyUtil.get30MinSignal(latest30, prev30);
        String signal30Desc = KlineStrategyUtil.get30MinSignalDesc(signal30);

        // 成交量分析
        boolean volumeConfirm10 = checkVolumeConfirmation(rolling10MinList);
        boolean volumeConfirm30 = checkVolumeConfirmation(rolling30MinList);

        // 市场状态
        String marketStatus = combineService.getMarketStatus(rolling10MinList);

        // 交易时段
        String tradingSession = combineService.getTradingSession(latest10.getEndTime());

        // 风险评估
        Map<String, Object> riskAssessment = assessRisk(rolling10MinList, rolling30MinList);

        // 综合信号
        String overallSignal = generateOverallSignal(signal10, signal30, marketStatus, riskAssessment);

        // 构建信号对象
        signals.put("10min_signal_code", signal10);
        signals.put("10min_signal", signal10Desc);
        signals.put("30min_signal_code", signal30);
        signals.put("30min_signal", signal30Desc);
        signals.put("volume_confirm_10min", volumeConfirm10);
        signals.put("volume_confirm_30min", volumeConfirm30);
        signals.put("market_status", marketStatus);
        signals.put("trading_session", tradingSession);
        signals.put("risk_assessment", riskAssessment);
        signals.put("overall_signal", overallSignal);
        signals.put("recommended_action", getRecommendedAction(signal10, signal30, overallSignal, riskAssessment));

        return signals;
    }

    /**
     * 检查成交量确认
     */
    private boolean checkVolumeConfirmation(List<Vallisusdt> klineList) {
        if (klineList.size() < 3) {
            return false;
        }

        // 获取最近3根K线
        Vallisusdt latest = klineList.get(klineList.size() - 1);
        Vallisusdt prev1 = klineList.get(klineList.size() - 2);
        Vallisusdt prev2 = klineList.get(klineList.size() - 3);

        try {
            BigDecimal latestVolume = new BigDecimal(latest.getCalcCount());
            BigDecimal prev1Volume = new BigDecimal(prev1.getCalcCount());
            BigDecimal prev2Volume = new BigDecimal(prev2.getCalcCount());

            // 计算平均成交量
            BigDecimal avgVolume = prev1Volume.add(prev2Volume).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

            // 最新成交量比平均高20%
            return latestVolume.compareTo(avgVolume.multiply(BigDecimal.valueOf(1.2))) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 风险评估
     */
    private Map<String, Object> assessRisk(List<Vallisusdt> rolling10MinList,
                                           List<Vallisusdt> rolling30MinList) {
        Map<String, Object> risk = new LinkedHashMap<>();

        try {
            // 计算波动率
            List<Double> changes10 = rolling10MinList.stream()
                    .map(KlineStrategyUtil::getChangePercentDecimal)
                    .collect(Collectors.toList());
            double volatility10 = KlineStrategyUtil.calculateVolatility(changes10);

            List<Double> changes30 = rolling30MinList.stream()
                    .map(KlineStrategyUtil::getChangePercentDecimal)
                    .collect(Collectors.toList());
            double volatility30 = KlineStrategyUtil.calculateVolatility(changes30);

            // 连续亏损风险
            int losingStreak = calculateLosingStreak();

            // 市场异常风险
            boolean highVolatility = volatility10 > 0.2 || volatility30 > 0.3;
            boolean lowLiquidity = checkLowLiquidity(rolling10MinList);

            // 时间风险
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            boolean riskyTime = (hour >= 20 && hour < 22) || (hour >= 8 && hour < 10); // 低流动性时段

            // 综合风险等级
            String riskLevel = "低";
            if (highVolatility || losingStreak >= 3 || riskyTime) {
                riskLevel = "高";
            } else if (volatility10 > 0.15 || lowLiquidity) {
                riskLevel = "中";
            }

            risk.put("volatility_10min", String.format("%.4f%%", volatility10));
            risk.put("volatility_30min", String.format("%.4f%%", volatility30));
            risk.put("losing_streak", losingStreak);
            risk.put("high_volatility", highVolatility);
            risk.put("low_liquidity", lowLiquidity);
            risk.put("risky_time", riskyTime);
            risk.put("risk_level", riskLevel);
            risk.put("recommended_position", calculateRecommendedPosition(riskLevel));

        } catch (Exception e) {
            log.error("风险评估异常", e);
        }

        return risk;
    }

    /**
     * 检查低流动性
     */
    private boolean checkLowLiquidity(List<Vallisusdt> klineList) {
        if (klineList.size() < 5) {
            return false;
        }

        try {
            // 计算最近5根K线的平均成交量
            double avgVolume = klineList.subList(klineList.size() - 5, klineList.size()).stream()
                    .mapToDouble(v -> Double.parseDouble(v.getCalcCount()))
                    .average()
                    .orElse(0.0);

            // 计算历史平均成交量（前20根）
            if (klineList.size() >= 25) {
                double historicalAvg = klineList.subList(klineList.size() - 25, klineList.size() - 5).stream()
                        .mapToDouble(v -> Double.parseDouble(v.getCalcCount()))
                        .average()
                        .orElse(0.0);

                // 如果当前成交量低于历史平均的60%，认为是低流动性
                return avgVolume < historicalAvg * 0.6;
            }
        } catch (Exception e) {
            // 忽略解析异常
        }

        return false;
    }

    /**
     * 计算连续亏损次数
     */
    private int calculateLosingStreak() {
        if (tradeHistory.isEmpty()) {
            return 0;
        }

        int streak = 0;
        for (int i = tradeHistory.size() - 1; i >= 0; i--) {
            Map<String, Object> trade = tradeHistory.get(i);
            Object profitObj = trade.get("profit_pct");
            if (profitObj instanceof Number) {
                double profit = ((Number) profitObj).doubleValue();
                if (profit < 0) {
                    streak++;
                } else {
                    break;
                }
            }
        }
        return streak;
    }

    /**
     * 计算建议仓位
     */
    private double calculateRecommendedPosition(String riskLevel) {
        switch (riskLevel) {
            case "高": return 0.005; // 0.5%
            case "中": return 0.01;  // 1%
            case "低": return 0.02;  // 2%
            default: return 0.01;
        }
    }

    /**
     * 生成综合信号
     */
    private String generateOverallSignal(int signal10, int signal30,
                                         String marketStatus, Map<String, Object> riskAssessment) {
        String riskLevel = (String) riskAssessment.get("risk_level");

        // 高风险时段不交易
        if ("高".equals(riskLevel)) {
            return "不交易";
        }

        // 根据市场状态调整
        if (marketStatus.contains("震荡市") || marketStatus.contains("低波动")) {
            // 震荡市优先使用10分钟策略
            if (signal10 != 0) {
                return KlineStrategyUtil.get10MinSignalDesc(signal10);
            }
        } else if (marketStatus.contains("趋势市") || marketStatus.contains("高波动")) {
            // 趋势市优先使用30分钟策略
            if (signal30 != 0) {
                return KlineStrategyUtil.get30MinSignalDesc(signal30);
            }
        }

        // 信号一致时增强
        if ((signal10 == 1 && signal30 == 1) || (signal10 == -1 && signal30 == -1)) {
            return "强烈" + KlineStrategyUtil.get10MinSignalDesc(signal10);
        }

        // 信号冲突时观望
        if ((signal10 == 1 && signal30 == -1) || (signal10 == -1 && signal30 == 1)) {
            return "信号冲突-观望";
        }

        return "观望";
    }

    /**
     * 获取建议操作
     */
    private String getRecommendedAction(int signal10, int signal30,
                                        String overallSignal, Map<String, Object> riskAssessment) {
        if ("不交易".equals(overallSignal) || "观望".equals(overallSignal) || "信号冲突-观望".equals(overallSignal)) {
            return "保持观望";
        }

        String riskLevel = (String) riskAssessment.get("risk_level");
        double position = calculateRecommendedPosition(riskLevel);

        if (overallSignal.startsWith("强烈")) {
            return String.format("开仓%s，仓位%.1f%%",
                    overallSignal.replace("强烈", ""), position * 100);
        } else {
            return String.format("轻仓%s，仓位%.1f%%", overallSignal, position * 50);
        }
    }

    /**
     * 检查持仓状态
     */
    private void checkPositionStatus(Vallisusdt latest10Min, Vallisusdt latest30Min) {
        if (currentPosition == null) {
            return;
        }

        try {
            String direction = (String) currentPosition.get("direction");
            BigDecimal entryPrice = new BigDecimal(currentPosition.get("entry_price").toString());
            BigDecimal stopLoss = new BigDecimal(currentPosition.get("stop_loss").toString());
            BigDecimal takeProfit = new BigDecimal(currentPosition.get("take_profit").toString());
            BigDecimal currentPrice = new BigDecimal(latest10Min.getEndPrice());

            boolean stopLossHit = false;
            boolean takeProfitHit = false;

            if ("做多".equals(direction)) {
                stopLossHit = currentPrice.compareTo(stopLoss) <= 0;
                takeProfitHit = currentPrice.compareTo(takeProfit) >= 0;
            } else if ("做空".equals(direction)) {
                stopLossHit = currentPrice.compareTo(stopLoss) >= 0;
                takeProfitHit = currentPrice.compareTo(takeProfit) <= 0;
            }

            if (stopLossHit || takeProfitHit) {
                // 平仓
                closePosition(stopLossHit ? "止损" : "止盈", currentPrice);
            }

        } catch (Exception e) {
            log.error("检查持仓状态异常", e);
        }
    }

    /**
     * 平仓
     */
    private void closePosition(String reason, BigDecimal exitPrice) {
        if (currentPosition == null) {
            return;
        }

        try {
            BigDecimal entryPrice = new BigDecimal(currentPosition.get("entry_price").toString());
            String direction = (String) currentPosition.get("direction");

            double profitPct;
            if ("做多".equals(direction)) {
                profitPct = exitPrice.subtract(entryPrice)
                        .divide(entryPrice, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
            } else {
                profitPct = entryPrice.subtract(exitPrice)
                        .divide(entryPrice, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
            }

            // 记录交易历史
            Map<String, Object> trade = new LinkedHashMap<>(currentPosition);
            trade.put("exit_time", new Date());
            trade.put("exit_price", exitPrice.toString());
            trade.put("exit_reason", reason);
            trade.put("profit_pct", profitPct);
            trade.put("profit_amount", profitPct * 0.01 * Double.parseDouble(currentPosition.get("position_size").toString()));

            tradeHistory.add(trade);

            // 清空持仓
            inPosition = false;
            currentPosition = null;

            log.info("平仓完成：{}，盈亏：{}%", reason, String.format("%.4f", profitPct));

        } catch (Exception e) {
            log.error("平仓异常", e);
        }
    }

    /**
     * 更新监控数据
     */
    private void updateMonitorData(List<Vallisusdt> oneMinList,
                                   List<Vallisusdt> rolling10MinList,
                                   List<Vallisusdt> rolling30MinList,
                                   Vallisusdt latest10Min, Vallisusdt latest30Min,
                                   double change10, double change30,
                                   int continuousCount, Map<String, Object> signals) {

        // 10分钟数据
        Map<String, Object> min10Map = new LinkedHashMap<>();
        min10Map.put("start_price", latest10Min.getStartPrice());
        min10Map.put("end_price", latest10Min.getEndPrice());
        min10Map.put("max_price", latest10Min.getMaxPrice());
        min10Map.put("min_price", latest10Min.getMinPrice());
        min10Map.put("change", String.format("%.4f%%", change10));
        min10Map.put("change_value", change10);
        min10Map.put("volume", latest10Min.getCalcCount());
        min10Map.put("turnover", latest10Min.getPriceCount());

        // 30分钟数据
        Map<String, Object> min30Map = new LinkedHashMap<>();
        min30Map.put("start_price", latest30Min.getStartPrice());
        min30Map.put("end_price", latest30Min.getEndPrice());
        min30Map.put("max_price", latest30Min.getMaxPrice());
        min30Map.put("min_price", latest30Min.getMinPrice());
        min30Map.put("change", String.format("%.4f%%", change30));
        min30Map.put("change_value", change30);
        min30Map.put("volume", latest30Min.getCalcCount());
        min30Map.put("turnover", latest30Min.getPriceCount());

        // 统计信息
        Map<String, Object> statistics = combineService.calculateRollingStatistics(oneMinList);

        // 更新最新数据
        latestData.clear();
        latestData.put("timestamp", new Date().getTime());
        latestData.put("update_time", new Date().toString());
        latestData.put("continuous_kline", continuousCount);
        latestData.put("min10", min10Map);
        latestData.put("min30", min30Map);
        latestData.put("signals", signals);
        latestData.put("statistics", statistics);
        latestData.put("market_status", signals.get("market_status"));
        latestData.put("trading_session", signals.get("trading_session"));
        latestData.put("in_position", inPosition);
        latestData.put("current_position", currentPosition);
        latestData.put("trade_history_count", tradeHistory.size());
        latestData.put("signal_history_count", signalHistory.size());
    }

    /**
     * 记录信号历史
     */
    private void recordSignalHistory(Map<String, Object> signals) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", new Date().getTime());
        record.put("time", new Date().toString());
        record.putAll(signals);

        signalHistory.add(0, record); // 添加到开头

        // 限制历史记录数量
        if (signalHistory.size() > MAX_HISTORY) {
            signalHistory = signalHistory.subList(0, MAX_HISTORY);
        }
    }

    /**
     * 日志输出
     */
    private void logMonitorInfo(double change10, double change30,
                                int continuousCount, Map<String, Object> signals) {
        String signal10 = (String) signals.get("10min_signal");
        String signal30 = (String) signals.get("30min_signal");
        String overallSignal = (String) signals.get("overall_signal");
        String marketStatus = (String) signals.get("market_status");
        String riskLevel = (String) ((Map<?, ?>) signals.get("risk_assessment")).get("risk_level");

        log.info("【实时监控】连续K线:{} | 市场状态:{} | 风险等级:{}",
                continuousCount, marketStatus, riskLevel);
        log.info("【信号分析】10分:{} ({}%) | 30分:{} ({}%) | 综合:{}",
                signal10, String.format("%.4f", change10),
                signal30, String.format("%.4f", change30),
                overallSignal);
    }

    /**
     * 获取实时数据
     */
    public Map<String, Object> getRealData() {
        return new LinkedHashMap<>(latestData);
    }

    /**
     * 获取信号历史
     */
    public List<Map<String, Object>> getSignalHistory(int limit) {
        if (limit <= 0 || limit > signalHistory.size()) {
            limit = Math.min(20, signalHistory.size());
        }
        return new ArrayList<>(signalHistory.subList(0, limit));
    }

    /**
     * 获取交易历史
     */
    public List<Map<String, Object>> getTradeHistory(int limit) {
        if (limit <= 0 || limit > tradeHistory.size()) {
            limit = Math.min(20, tradeHistory.size());
        }
        return new ArrayList<>(tradeHistory.subList(0, limit));
    }

    /**
     * 手动开仓（测试用）
     */
    public Map<String, Object> openPosition(String direction, double positionSize) {
        if (inPosition) {
            // 修复：不使用Map.of()，使用传统方式
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "已有持仓");
            return result;
        }

        try {
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(10);
            if (oneMinList.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "无法获取价格");
                return result;
            }

            Vallisusdt latest = oneMinList.get(oneMinList.size() - 1);
            BigDecimal entryPrice = new BigDecimal(latest.getEndPrice());

            // 计算止损止盈
            BigDecimal stopLoss, takeProfit;
            double stopLossPct = KlineStrategyUtil.Q95_THRESHOLD / 100.0;
            double takeProfitPct = KlineStrategyUtil.TARGET_RETURN / 100.0;

            if ("做多".equals(direction)) {
                stopLoss = entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(stopLossPct)));
                takeProfit = entryPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(takeProfitPct)));
            } else {
                stopLoss = entryPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(stopLossPct)));
                takeProfit = entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(takeProfitPct)));
            }

            // 创建持仓记录
            currentPosition = new LinkedHashMap<>();
            currentPosition.put("entry_time", new Date());
            currentPosition.put("entry_price", entryPrice.toString());
            currentPosition.put("direction", direction);
            currentPosition.put("position_size", positionSize);
            currentPosition.put("stop_loss", stopLoss.toString());
            currentPosition.put("take_profit", takeProfit.toString());

            inPosition = true;

            // 修复：不使用Map.of()，使用传统方式
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "开仓成功");
            result.put("position", currentPosition);
            return result;

        } catch (Exception e) {
            log.error("手动开仓异常", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "开仓异常: " + e.getMessage());
            return result;
        }
    }

    /**
     * 手动平仓（测试用）
     */
    public Map<String, Object> closePosition() {
        if (!inPosition || currentPosition == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "没有持仓");
            return result;
        }

        try {
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(10);
            if (oneMinList.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "无法获取价格");
                return result;
            }

            Vallisusdt latest = oneMinList.get(oneMinList.size() - 1);
            BigDecimal exitPrice = new BigDecimal(latest.getEndPrice());

            closePosition("手动平仓", exitPrice);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "平仓成功");
            result.put("exit_price", exitPrice.toString());
            return result;

        } catch (Exception e) {
            log.error("手动平仓异常", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "平仓异常: " + e.getMessage());
            return result;
        }
    }
}