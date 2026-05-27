package com.alphay.boot.web.controller.vallix;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class KlineCombineService {


    // ===================== 核心修复：正确的滚动窗口计算 =====================

    /**
     * 滚动10分钟窗口计算（每分钟计算一次）
     * 正确实现：每次滑动1分钟，计算最近10分钟的数据
     */
    public List<Vallisusdt> calculateRolling10Min(List<Vallisusdt> oneMinList) {
        if (oneMinList == null || oneMinList.size() < 10) {
            log.warn("1分钟数据不足10根，无法计算滚动10分钟窗口");
            return new ArrayList<>();
        }

        List<Vallisusdt> rolling10Min = new ArrayList<>();

        // 从第10根开始，每次滑动1根（滚动窗口）
        for (int i = 9; i < oneMinList.size(); i++) {
            // 取最近10根1分钟K线
            List<Vallisusdt> window = oneMinList.subList(i - 9, i + 1);
            Vallisusdt rollingKline = buildKline(window);

            // 设置正确的时间（窗口的开始和结束时间）
            rollingKline.setStartTime(window.get(0).getStartTime());
            rollingKline.setEndTime(window.get(window.size() - 1).getEndTime());

            // 添加标记，表示这是滚动窗口计算
            rollingKline.setRemark("ROLLING_10MIN_" + rollingKline.getStartTime().getTime());

            rolling10Min.add(rollingKline);
        }

        log.info("滚动10分钟计算完成，生成 {} 个窗口", rolling10Min.size());
        return rolling10Min;
    }

    /**
     * 滚动30分钟窗口计算
     */
    public List<Vallisusdt> calculateRolling30Min(List<Vallisusdt> oneMinList) {
        if (oneMinList == null || oneMinList.size() < 30) {
            log.warn("1分钟数据不足30根，无法计算滚动30分钟窗口");
            return new ArrayList<>();
        }

        List<Vallisusdt> rolling30Min = new ArrayList<>();

        for (int i = 29; i < oneMinList.size(); i++) {
            List<Vallisusdt> window = oneMinList.subList(i - 29, i + 1);
            Vallisusdt rollingKline = buildKline(window);

            rollingKline.setStartTime(window.get(0).getStartTime());
            rollingKline.setEndTime(window.get(window.size() - 1).getEndTime());
            rollingKline.setRemark("ROLLING_30MIN_" + rollingKline.getStartTime().getTime());

            rolling30Min.add(rollingKline);
        }

        log.info("滚动30分钟计算完成，生成 {} 个窗口", rolling30Min.size());
        return rolling30Min;
    }

    /**
     * 获取最新的滚动10分钟K线
     */
    public Vallisusdt getLatestRolling10Min(List<Vallisusdt> oneMinList) {
        List<Vallisusdt> rollingList = calculateRolling10Min(oneMinList);
        if (rollingList.isEmpty()) {
            return null;
        }
        return rollingList.get(rollingList.size() - 1);
    }

    /**
     * 获取最新的滚动30分钟K线
     */
    public Vallisusdt getLatestRolling30Min(List<Vallisusdt> oneMinList) {
        List<Vallisusdt> rollingList = calculateRolling30Min(oneMinList);
        if (rollingList.isEmpty()) {
            return null;
        }
        return rollingList.get(rollingList.size() - 1);
    }

    // ===================== 自然时间K线（用于显示） =====================

    /**
     * 自然时间10分钟K线（用于显示，不用于策略）
     */
    public List<Vallisusdt> buildNatural10MinList(List<Vallisusdt> oneMinList) {
        if (oneMinList == null || oneMinList.isEmpty()) {
            return new ArrayList<>();
        }

        // 按10分钟分组
        Map<Long, List<Vallisusdt>> groupBy10Min = oneMinList.stream()
                .collect(Collectors.groupingBy(k -> {
                    long tenMinuteStart = (k.getStartTime().getTime() / (10 * 60 * 1000L)) * (10 * 60 * 1000L);
                    return tenMinuteStart;
                }));

        return groupBy10Min.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Vallisusdt kline = buildKline(entry.getValue());
                    kline.setRemark("NATURAL_10MIN");
                    return kline;
                })
                .collect(Collectors.toList());
    }

    /**
     * 自然时间30分钟K线（用于显示，不用于策略）
     */
    public List<Vallisusdt> buildNatural30MinList(List<Vallisusdt> oneMinList) {
        if (oneMinList == null || oneMinList.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, List<Vallisusdt>> groupBy30Min = oneMinList.stream()
                .collect(Collectors.groupingBy(k -> {
                    long thirtyMinuteStart = (k.getStartTime().getTime() / (30 * 60 * 1000L)) * (30 * 60 * 1000L);
                    return thirtyMinuteStart;
                }));

        return groupBy30Min.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Vallisusdt kline = buildKline(entry.getValue());
                    kline.setRemark("NATURAL_30MIN");
                    return kline;
                })
                .collect(Collectors.toList());
    }

    // ===================== 核心：构建K线 =====================

    private Vallisusdt buildKline(List<Vallisusdt> group) {
        if (group == null || group.isEmpty()) {
            return null;
        }

        Vallisusdt res = new Vallisusdt();

        try {
            // 开/收
            res.setStartPrice(group.get(0).getStartPrice());
            res.setEndPrice(group.get(group.size() - 1).getEndPrice());

            // 高/低
            res.setMaxPrice(group.stream()
                    .map(v -> new BigDecimal(v.getMaxPrice()))
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO)
                    .setScale(8, RoundingMode.HALF_UP)
                    .toPlainString());

            res.setMinPrice(group.stream()
                    .map(v -> new BigDecimal(v.getMinPrice()))
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO)
                    .setScale(8, RoundingMode.HALF_UP)
                    .toPlainString());

            // 成交量/成交额
            BigDecimal calcCount = group.stream()
                    .map(v -> new BigDecimal(v.getCalcCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            res.setCalcCount(calcCount.setScale(2, RoundingMode.HALF_UP).toPlainString());

            BigDecimal priceCount = group.stream()
                    .map(v -> new BigDecimal(v.getPriceCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            res.setPriceCount(priceCount.setScale(2, RoundingMode.HALF_UP).toPlainString());

            res.setNumCount(String.valueOf(
                    group.stream().mapToInt(v -> {
                        try {
                            return Integer.parseInt(v.getNumCount());
                        } catch (Exception e) {
                            return 0;
                        }
                    }).sum()
            ));

            BigDecimal zdBuyCount = group.stream()
                    .map(v -> new BigDecimal(v.getZdBuyCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            res.setZdBuyCount(zdBuyCount.setScale(2, RoundingMode.HALF_UP).toPlainString());

            BigDecimal zdSellCount = group.stream()
                    .map(v -> new BigDecimal(v.getZdSellCount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            res.setZdSellCount(zdSellCount.setScale(2, RoundingMode.HALF_UP).toPlainString());

            res.setCreateTime(new Date());
            res.setUpdateTime(new Date());

        } catch (Exception e) {
            log.error("构建K线异常", e);
        }

        return res;
    }

    // ===================== 统计方法 =====================

    /**
     * 计算滚动窗口的统计信息
     */
    public Map<String, Object> calculateRollingStatistics(List<Vallisusdt> oneMinList) {
        Map<String, Object> stats = new LinkedHashMap<>();

        try {
            // 计算滚动10分钟统计
            List<Vallisusdt> rolling10Min = calculateRolling10Min(oneMinList);
            if (!rolling10Min.isEmpty()) {
                List<Double> changes10 = rolling10Min.stream()
                        .map(KlineStrategyUtil::getChangePercentDecimal)
                        .collect(Collectors.toList());

                stats.put("10min_rolling_count", rolling10Min.size());
                stats.put("10min_avg_change", KlineStrategyUtil.calculateAverageChange(changes10));
                stats.put("10min_std_change", KlineStrategyUtil.calculateVolatility(changes10));
                stats.put("10min_up_ratio", KlineStrategyUtil.calculateUpRatio(rolling10Min) * 100);

                // 关键分位数
                List<Double> absChanges = changes10.stream()
                        .map(Math::abs)
                        .sorted()
                        .collect(Collectors.toList());

                if (!absChanges.isEmpty()) {
                    int q75Index = (int) (absChanges.size() * 0.75);
                    int q95Index = (int) (absChanges.size() * 0.95);

                    stats.put("10min_q75", absChanges.get(Math.min(q75Index, absChanges.size() - 1)) * 100);
                    stats.put("10min_q95", absChanges.get(Math.min(q95Index, absChanges.size() - 1)) * 100);
                }
            }

            // 计算滚动30分钟统计
            List<Vallisusdt> rolling30Min = calculateRolling30Min(oneMinList);
            if (!rolling30Min.isEmpty()) {
                List<Double> changes30 = rolling30Min.stream()
                        .map(KlineStrategyUtil::getChangePercentDecimal)
                        .collect(Collectors.toList());

                stats.put("30min_rolling_count", rolling30Min.size());
                stats.put("30min_avg_change", KlineStrategyUtil.calculateAverageChange(changes30));
                stats.put("30min_std_change", KlineStrategyUtil.calculateVolatility(changes30));
                stats.put("30min_up_ratio", KlineStrategyUtil.calculateUpRatio(rolling30Min) * 100);

                // 年化波动率
                double dailyVolatility = KlineStrategyUtil.calculateVolatility(changes30);
                double annualVolatility = dailyVolatility * Math.sqrt(365);
                stats.put("30min_annual_volatility", annualVolatility);
            }

        } catch (Exception e) {
            log.error("计算统计信息异常", e);
        }

        return stats;
    }

    /**
     * 获取市场状态
     */
    public String getMarketStatus(List<Vallisusdt> oneMinList) {
        try {
            List<Vallisusdt> rolling10Min = calculateRolling10Min(oneMinList);
            if (rolling10Min.size() < 10) {
                return "数据不足";
            }

            // 计算最近10个10分钟窗口的变化
            List<Double> recentChanges = rolling10Min.subList(rolling10Min.size() - 10, rolling10Min.size())
                    .stream()
                    .map(KlineStrategyUtil::getChangePercent)
                    .collect(Collectors.toList());

            double volatility = KlineStrategyUtil.calculateVolatility(
                    recentChanges.stream().map(v -> v / 100.0).collect(Collectors.toList())
            );

            // 判断市场状态
            if (volatility < 0.05) {
                return "震荡市（低波动）";
            } else if (volatility > 0.15) {
                return "趋势市（高波动）";
            } else {
                return "平衡市（正常波动）";
            }

        } catch (Exception e) {
            log.error("判断市场状态异常", e);
            return "未知";
        }
    }

    /**
     * 获取交易时段
     */
    public String getTradingSession(Date time) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        // UTC时间（币安时间）
        if (hour >= 0 && hour < 8) {
            return "亚洲时段（高流动性）";
        } else if (hour >= 8 && hour < 16) {
            return "欧洲时段（正常流动性）";
        } else {
            return "美洲时段（高波动性）";
        }
    }
}