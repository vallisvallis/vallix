package com.alphay.boot.web.controller.vallix;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class KlineStrategyUtil {
    // ===================== 策略阈值 =====================
    public static final double Q75_THRESHOLD = 0.056;    // 75%分位数入场阈值
    public static final double Q95_THRESHOLD = 0.198;    // 95%分位数止损阈值
    public static final double TARGET_RETURN = 0.005;    // 均值回归目标
    public static final double MAX_LOSS = 0.3;           // 最大止损（30分钟策略）
    public static final double MAX_PROFIT = 0.6;         // 最大止盈（30分钟策略）

    /**
     * 判断K线是否上涨
     */
    public static boolean isUp(Vallisusdt vo) {
        BigDecimal start = new BigDecimal(vo.getStartPrice());
        BigDecimal end = new BigDecimal(vo.getEndPrice());
        return end.compareTo(start) > 0;
    }

    /**
     * 计算涨跌幅 %（保留4位小数）
     */
    public static double getChangePercent(Vallisusdt vo) {
        if (vo == null || vo.getStartPrice() == null || vo.getEndPrice() == null) {
            return 0.0;
        }
        BigDecimal start = new BigDecimal(vo.getStartPrice());
        BigDecimal end = new BigDecimal(vo.getEndPrice());

        if (start.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }

        return end.subtract(start)
                .divide(start, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .doubleValue();
    }

    /**
     * 计算涨跌幅（小数形式，用于统计计算）
     */
    public static double getChangePercentDecimal(Vallisusdt vo) {
        return getChangePercent(vo) / 100.0;
    }

    /**
     * 统计连续同向K线数量
     */
    public static int countContinuous(List<Vallisusdt> klineList) {
        if (klineList == null || klineList.size() < 2) {
            return 0;
        }

        int count = 1;
        boolean firstUp = isUp(klineList.get(0));

        for (int i = 1; i < klineList.size(); i++) {
            if (isUp(klineList.get(i)) == firstUp) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * 计算波动率（标准差）
     */
    public static double calculateVolatility(List<Double> changes) {
        if (changes == null || changes.size() < 2) {
            return 0.0;
        }

        double mean = changes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = changes.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
        return Math.sqrt(variance) * 100; // 转换为百分比
    }

    /**
     * 计算平均变化
     */
    public static double calculateAverageChange(List<Double> changes) {
        if (changes == null || changes.isEmpty()) {
            return 0.0;
        }
        return changes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) * 100;
    }

    /**
     * 计算上涨比例
     */
    public static double calculateUpRatio(List<Vallisusdt> klineList) {
        if (klineList == null || klineList.isEmpty()) {
            return 0.0;
        }

        long upCount = klineList.stream().filter(KlineStrategyUtil::isUp).count();
        return (double) upCount / klineList.size();
    }

    /**
     * 10分钟均值回归策略信号
     * @return 0=观望, 1=做多, -1=做空, 2=止损
     */
    public static int get10MinSignal(Vallisusdt kline) {
        double change = getChangePercent(kline);
        double absChange = Math.abs(change);

        if (absChange > Q95_THRESHOLD) {
            return 2; // 止损信号
        } else if (change > Q75_THRESHOLD) {
            return -1; // 做空（涨多了）
        } else if (change < -Q75_THRESHOLD) {
            return 1;  // 做多（跌多了）
        } else {
            return 0;  // 观望
        }
    }

    /**
     * 10分钟信号描述
     */
    public static String get10MinSignalDesc(int signal) {
        switch (signal) {
            case 1: return "做多";
            case -1: return "做空";
            case 2: return "止损";
            default: return "观望";
        }
    }

    /**
     * 30分钟趋势突破策略信号
     * @return 0=观望, 1=做多, -1=做空
     */
    public static int get30MinSignal(Vallisusdt current, Vallisusdt previous) {
        if (current == null || previous == null) {
            return 0;
        }

        BigDecimal currentHigh = new BigDecimal(current.getMaxPrice());
        BigDecimal previousHigh = new BigDecimal(previous.getMaxPrice());
        BigDecimal currentLow = new BigDecimal(current.getMinPrice());
        BigDecimal previousLow = new BigDecimal(previous.getMinPrice());

        boolean breakUp = currentHigh.compareTo(previousHigh) > 0;
        boolean breakDown = currentLow.compareTo(previousLow) < 0;

        if (breakUp) {
            return 1; // 做多
        } else if (breakDown) {
            return -1; // 做空
        } else {
            return 0; // 观望
        }
    }

    /**
     * 30分钟信号描述
     */
    public static String get30MinSignalDesc(int signal) {
        switch (signal) {
            case 1: return "做多";
            case -1: return "做空";
            default: return "观望";
        }
    }

    /**
     * 计算建议仓位（基于凯利公式简化版）
     */
    public static double calculatePositionSize(double winRate, double profitLossRatio, double maxRiskPercent) {
        // 凯利公式：f* = (p × b - q) / b
        double p = winRate / 100.0; // 转换为小数
        double q = 1 - p;
        double b = profitLossRatio;

        double kelly = (p * b - q) / b;

        // 使用1/4凯利，并限制最大风险
        double position = kelly * 0.25;
        return Math.min(position, maxRiskPercent / 100.0);
    }

    /**
     * 计算风险回报比
     */
    public static double calculateRiskRewardRatio(double entryPrice, double stopLoss, double takeProfit) {
        double risk = Math.abs(entryPrice - stopLoss);
        double reward = Math.abs(takeProfit - entryPrice);

        if (risk == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return reward / risk;
    }
}
