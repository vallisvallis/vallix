package com.alphay.boot.web.controller.vallix;


import com.alphay.boot.common.core.controller.BaseController;
import com.alphay.boot.common.core.domain.AjaxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【请填写功能名称】Controller
 *
 * @author ruoyi
 * @date 2025-05-26
 */
@RestController
@Slf4j
@RequestMapping("/system/vallisusdt")
public class VallisusdtController extends BaseController {
    @Autowired
    private BackTestService backTestService;

    @Autowired
    private RealTimeMonitorService realTimeMonitorService;

    @Autowired
    private KlineCombineService combineService;

    @Autowired
    private VallisUsdtEventTool vallisUsdtEventTool;

    @Autowired
    private KlineCacheService klineCacheService;

    @Autowired
    private BtcFeatureEngineeringService featureEngineeringService;

    /**
     * BTC特征工程回测分析
     */
    @GetMapping("/btc/feature/backtest")
    public AjaxResult backtestWithFeatures(@RequestParam(defaultValue = "500") int dataPoints,
                                           @RequestParam(defaultValue = "5") int predictWindow) {
        try {
            log.info("开始BTC特征工程回测，数据点数: {}, 预测窗口: {}分钟", dataPoints, predictWindow);
            
            // 限制最大数据点数，避免超时
            if (dataPoints > 2000) {
                dataPoints = 2000;
                log.warn("数据点数超过限制，已调整为2000");
            }
            
            // 获取足够的历史数据（需要额外的数据用于计算标签）
            List<Vallisusdt> klineData = vallisUsdtEventTool.getBtc1minKline(dataPoints + predictWindow + 30);
            
            if (klineData == null || klineData.size() < 50) {
                return AjaxResult.error("数据不足，至少需要50条数据");
            }
            
            log.info("获取到{}条K线数据", klineData.size());
            
            // 执行回测
            BtcFeatureEngineeringService.StatisticsResult result = 
                featureEngineeringService.backtest(klineData, predictWindow);
            
            if (result == null) {
                return AjaxResult.error("回测结果为空");
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("totalSamples", result.getTotalSamples());
            response.put("validSamples", result.getValidSamples());
            response.put("singleFactors", buildFactorResponse(result));
            response.put("comboStrategies", buildComboResponse(result));
            response.put("timestamp", new Date());
            
            return AjaxResult.success(response);
            
        } catch (Exception e) {
            log.error("BTC特征工程回测异常", e);
            return AjaxResult.error("回测失败: " + e.getMessage());
        }
    }
    
    private Map<String, Object> buildFactorResponse(BtcFeatureEngineeringService.StatisticsResult stats) {
        Map<String, Object> factors = new HashMap<>();
        
        if (stats.getInertiaStats() != null) {
            factors.put("inertia", convertFactorToMap(stats.getInertiaStats()));
        }
        if (stats.getPositionStats() != null) {
            factors.put("position", convertFactorToMap(stats.getPositionStats()));
        }
        if (stats.getMaStats() != null) {
            factors.put("ma", convertFactorToMap(stats.getMaStats()));
        }
        if (stats.getRsiStats() != null) {
            factors.put("rsi", convertFactorToMap(stats.getRsiStats()));
        }
        if (stats.getVolStats() != null) {
            factors.put("volume", convertFactorToMap(stats.getVolStats()));
        }
        
        return factors;
    }
    
    private Map<String, Object> buildComboResponse(BtcFeatureEngineeringService.StatisticsResult stats) {
        Map<String, Object> combos = new HashMap<>();
        
        if (stats.getCombo1Stats() != null) {
            combos.put("combo1_technical", convertFactorToMap(stats.getCombo1Stats()));
        }
        if (stats.getCombo2Stats() != null) {
            combos.put("combo2_micro", convertFactorToMap(stats.getCombo2Stats()));
        }
        if (stats.getCombo3Stats() != null) {
            combos.put("combo3_strong", convertFactorToMap(stats.getCombo3Stats()));
        }
        if (stats.getCombo4Stats() != null) {
            combos.put("combo4_trend_strict", convertFactorToMap(stats.getCombo4Stats()));
        }
        if (stats.getCombo5Stats() != null) {
            combos.put("combo5_reversion_strict", convertFactorToMap(stats.getCombo5Stats()));
        }
        if (stats.getCombo6Stats() != null) {
            combos.put("combo6_momentum_strict", convertFactorToMap(stats.getCombo6Stats()));
        }
        if (stats.getCombo7Stats() != null) {
            combos.put("combo7_multi_resonance", convertFactorToMap(stats.getCombo7Stats()));
        } else {
            log.warn("combo7Stats 为 null，跳过该策略");
        }
        
        return combos;
    }
    
    private Map<String, Object> convertFactorToMap(BtcFeatureEngineeringService.FactorStats stats) {
        if (stats == null) {
            return new HashMap<>();
        }
        
        Map<String, Object> map = new HashMap<>();
        map.put("factorName", stats.getFactorName());
        map.put("totalPredictions", stats.getTotalPredictions());
        map.put("correctPredictions", stats.getCorrectPredictions());
        map.put("accuracy", String.format("%.2f", stats.getAccuracy()));
        map.put("longPredictions", stats.getLongPredictions());
        map.put("longAccuracy", String.format("%.2f", stats.getLongAccuracy()));
        map.put("shortPredictions", stats.getShortPredictions());
        map.put("shortAccuracy", String.format("%.2f", stats.getShortAccuracy()));
        return map;
    }

    // ===================== 回测接口 =====================

    /**
     * 策略A回测（10分钟均值回归）
     */
    @GetMapping("/backtest/strategyA")
    public AjaxResult backtestStrategyA(@RequestParam(defaultValue = "2000") int dataPoints) {
        try {
            BackTestService.BackTestResult result = backTestService.backtestStrategyA(dataPoints);
            return AjaxResult.success("策略A回测完成", result.toMap());
        } catch (Exception e) {
            return AjaxResult.error("策略A回测失败: " + e.getMessage());
        }
    }

    /**
     * 策略B回测（30分钟趋势突破）
     */
    @GetMapping("/backtest/strategyB")
    public AjaxResult backtestStrategyB(@RequestParam(defaultValue = "2000") int dataPoints) {
        try {
            BackTestService.BackTestResult result = backTestService.backtestStrategyB(dataPoints);
            return AjaxResult.success("策略B回测完成", result.toMap());
        } catch (Exception e) {
            return AjaxResult.error("策略B回测失败: " + e.getMessage());
        }
    }

    /**
     * 综合回测（两个策略）
     */
    @GetMapping("/backtest/comprehensive")
    public AjaxResult comprehensiveBackTest(@RequestParam(defaultValue = "2000") int dataPoints) {
        try {
            Map<String, Object> result = backTestService.comprehensiveBackTest(dataPoints);
            return AjaxResult.success("综合回测完成", result);
        } catch (Exception e) {
            return AjaxResult.error("综合回测失败: " + e.getMessage());
        }
    }

    /**
     * 兼容原有接口
     */
    @GetMapping("/backtest")
    public AjaxResult backTest() {
        try {
            BackTestService.BackTestResult result = backTestService.startBackTest();
            Map<String, Object> map = new HashMap<>();
            map.put("策略名称", result.getStrategyName());
            map.put("总交易次数", result.getTotalTrades());
            map.put("盈利次数", result.getWinningTrades());
            map.put("胜率%", String.format("%.2f", result.getWinRate()));
            map.put("总收益%", String.format("%.4f", result.getTotalProfit()));
            map.put("最大回撤%", String.format("%.2f", result.getMaxDrawdown()));
            map.put("夏普比率", String.format("%.2f", result.getSharpeRatio()));
            map.put("盈亏比", String.format("%.2f", result.getProfitLossRatio()));
            return AjaxResult.success(map);
        } catch (Exception e) {
            return AjaxResult.error("回测失败: " + e.getMessage());
        }
    }

    // ===================== 实时监控接口 =====================

    /**
     * 获取实时数据
     */
    @GetMapping("/real")
    public AjaxResult getRealData() {
        try {
            Map<String, Object> data = realTimeMonitorService.getRealData();
            return AjaxResult.success(data);
        } catch (Exception e) {
            return AjaxResult.error("获取实时数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取信号历史
     */
    @GetMapping("/signals/history")
    public AjaxResult getSignalHistory(@RequestParam(defaultValue = "20") int limit) {
        try {
            List<Map<String, Object>> history = realTimeMonitorService.getSignalHistory(limit);
            return AjaxResult.success(history);
        } catch (Exception e) {
            return AjaxResult.error("获取信号历史失败: " + e.getMessage());
        }
    }

    /**
     * 获取交易历史
     */
    @GetMapping("/trades/history")
    public AjaxResult getTradeHistory(@RequestParam(defaultValue = "20") int limit) {
        try {
            List<Map<String, Object>> history = realTimeMonitorService.getTradeHistory(limit);
            return AjaxResult.success(history);
        } catch (Exception e) {
            return AjaxResult.error("获取交易历史失败: " + e.getMessage());
        }
    }

    // ===================== 统计分析接口 =====================

    /**
     * 获取统计信息
     */
    @GetMapping("/statistics")
    public AjaxResult getStatistics(@RequestParam(defaultValue = "1000") int dataPoints) {
        try {
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(dataPoints);
            Map<String, Object> stats = combineService.calculateRollingStatistics(oneMinList);
            return AjaxResult.success(stats);
        } catch (Exception e) {
            return AjaxResult.error("获取统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取市场状态
     */
    @GetMapping("/market/status")
    public AjaxResult getMarketStatus(@RequestParam(defaultValue = "500") int dataPoints) {
        try {
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(dataPoints);
            String status = combineService.getMarketStatus(oneMinList);

            // 修复：不使用Map.of()，使用传统方式
            Map<String, Object> result = new HashMap<>();
            result.put("market_status", status);
            return AjaxResult.success(result);

        } catch (Exception e) {
            return AjaxResult.error("获取市场状态失败: " + e.getMessage());
        }
    }

    // ===================== 交易管理接口 =====================

    /**
     * 手动开仓（测试用）
     */
    @PostMapping("/trade/open")
    public AjaxResult openPosition(@RequestParam String direction,
                                   @RequestParam(defaultValue = "0.01") double positionSize) {
        try {
            if (!"做多".equals(direction) && !"做空".equals(direction)) {
                return AjaxResult.error("方向参数错误，必须是'做多'或'做空'");
            }

            Map<String, Object> result = realTimeMonitorService.openPosition(direction, positionSize);
            return AjaxResult.success(result);
        } catch (Exception e) {
            return AjaxResult.error("开仓失败: " + e.getMessage());
        }
    }

    /**
     * 手动平仓（测试用）
     */
    @PostMapping("/trade/close")
    public AjaxResult closePosition() {
        try {
            Map<String, Object> result = realTimeMonitorService.closePosition();
            return AjaxResult.success(result);
        } catch (Exception e) {
            return AjaxResult.error("平仓失败: " + e.getMessage());
        }
    }

    /**
     * 获取持仓状态
     */
    @GetMapping("/trade/position")
    public AjaxResult getPositionStatus() {
        try {
            Map<String, Object> realData = realTimeMonitorService.getRealData();
            boolean inPosition = (boolean) realData.getOrDefault("in_position", false);
            Object currentPosition = realData.getOrDefault("current_position", null);

            Map<String, Object> result = new HashMap<>();
            result.put("in_position", inPosition);
            result.put("current_position", currentPosition);
            result.put("trade_history_count", realData.get("trade_history_count"));

            return AjaxResult.success(result);
        } catch (Exception e) {
            return AjaxResult.error("获取持仓状态失败: " + e.getMessage());
        }
    }

    // ===================== 策略参数接口 =====================

    /**
     * 获取策略参数
     */
    @GetMapping("/strategy/parameters")
    public AjaxResult getStrategyParameters() {
        try {
            Map<String, Object> params = new HashMap<>();

            // 10分钟策略参数
            params.put("10min_entry_threshold", KlineStrategyUtil.Q75_THRESHOLD);
            params.put("10min_stop_loss_threshold", KlineStrategyUtil.Q95_THRESHOLD);
            params.put("10min_target_return", KlineStrategyUtil.TARGET_RETURN);
            params.put("10min_hold_period", "10-20分钟");

            // 30分钟策略参数
            params.put("30min_stop_loss", KlineStrategyUtil.MAX_LOSS);
            params.put("30min_take_profit", KlineStrategyUtil.MAX_PROFIT);
            params.put("30min_hold_period", "30-90分钟");

            // 风险管理参数
            params.put("max_position_size", "2%");
            params.put("daily_max_loss", "5%");
            params.put("weekly_max_loss", "15%");
            params.put("max_consecutive_losses", 3);

            // 交易时段参数
            params.put("best_trading_hours", "UTC 0:00-8:00（亚洲时段）");
            params.put("avoid_trading_hours", "UTC 8:00-10:00, 20:00-22:00");
            params.put("max_daily_trades", 10);

            return AjaxResult.success(params);
        } catch (Exception e) {
            return AjaxResult.error("获取策略参数失败: " + e.getMessage());
        }
    }

    /**
     * 更新策略参数
     */
    @PostMapping("/strategy/parameters/update")
    public AjaxResult updateStrategyParameters(@RequestBody Map<String, Object> newParams) {
        try {
            // 这里可以添加参数验证和更新逻辑
            // 注意：实际项目中应该将参数保存到数据库或配置文件

            Map<String, Object> result = new HashMap<>();
            result.put("message", "参数更新成功（演示模式）");
            result.put("new_parameters", newParams);
            result.put("warning", "实际项目中需要持久化存储");

            return AjaxResult.success(result);
        } catch (Exception e) {
            return AjaxResult.error("更新策略参数失败: " + e.getMessage());
        }
    }

    // ===================== 系统健康检查 =====================

    /**
     * 系统健康检查
     */
    @GetMapping("/health")
    public AjaxResult healthCheck() {
        try {
            Map<String, Object> health = new HashMap<>();

            // 检查数据源
            List<Vallisusdt> testData = vallisUsdtEventTool.getEth1minKline(10);
            health.put("data_source", testData.isEmpty() ? "异常" : "正常");
            health.put("data_count", testData.size());

            // 检查服务
            health.put("backtest_service", backTestService != null ? "正常" : "异常");
            health.put("monitor_service", realTimeMonitorService != null ? "正常" : "异常");
            health.put("combine_service", combineService != null ? "正常" : "异常");

            // 获取系统时间
            health.put("system_time", new Date().toString());
            health.put("timezone", "Asia/Shanghai");

            // 内存信息
            Runtime runtime = Runtime.getRuntime();
            health.put("memory_total", runtime.totalMemory() / 1024 / 1024 + " MB");
            health.put("memory_free", runtime.freeMemory() / 1024 / 1024 + " MB");
            health.put("memory_used", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024 + " MB");

            return AjaxResult.success(health);
        } catch (Exception e) {
            return AjaxResult.error("健康检查失败: " + e.getMessage());
        }
    }

    // ===================== 数据管理接口 =====================

    /**
     * 获取原始K线数据
     */
    @GetMapping("/data/raw")
    public AjaxResult getRawKlineData(@RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(defaultValue = "1m") String interval) {
        try {
            if (!"1m".equals(interval)) {
                return AjaxResult.error("目前只支持1分钟K线");
            }

            List<Vallisusdt> data = vallisUsdtEventTool.getEth1minKline(limit);
            return AjaxResult.success(data);
        } catch (Exception e) {
            return AjaxResult.error("获取原始数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取BTC原始K线数据
     */
    @GetMapping("/btc/data/raw")
    public AjaxResult getBtcRawKlineData(@RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(defaultValue = "1m") String interval) {
        try {
            if (!"1m".equals(interval)) {
                return AjaxResult.error("目前只支持1分钟K线");
            }

            List<Vallisusdt> data = vallisUsdtEventTool.getBtc1minKline(limit);
            return AjaxResult.success(data);
        } catch (Exception e) {
            return AjaxResult.error("获取BTC原始数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取聚合K线数据
     */
    @GetMapping("/data/aggregated")
    public AjaxResult getAggregatedKlineData(@RequestParam(defaultValue = "1000") int oneMinLimit,
                                             @RequestParam String timeframe) {
        try {
            long startTime = System.currentTimeMillis();
            
            String cacheKey = String.format("kline_%d_%s", oneMinLimit, timeframe);
            
            Map<String, Object> cachedData = klineCacheService.get(cacheKey);
            if (cachedData != null) {
                log.info("缓存命中: {}, 耗时: {}ms", cacheKey, System.currentTimeMillis() - startTime);
                return AjaxResult.success(cachedData);
            }
            
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(oneMinLimit);
            log.info("获取ETH 1分钟数据: {}条, 耗时: {}ms", oneMinList.size(), System.currentTimeMillis() - startTime);

            Map<String, Object> result = new HashMap<>();
            result.put("one_minute_count", oneMinList.size());

            switch (timeframe) {
                case "10min":
                    List<Vallisusdt> rolling10Min = combineService.calculateRolling10Min(oneMinList);
                    result.put("rolling_10min", rolling10Min);
                    result.put("10min_count", rolling10Min.size());
                    break;

                case "30min":
                    List<Vallisusdt> rolling30Min = combineService.calculateRolling30Min(oneMinList);
                    result.put("rolling_30min", rolling30Min);
                    result.put("30min_count", rolling30Min.size());
                    break;

                case "60min":
                    List<Vallisusdt> rolling60Min = combineService.calculateRolling60Min(oneMinList);
                    result.put("rolling_60min", rolling60Min);
                    result.put("60min_count", rolling60Min.size());
                    break;

                case "all":
                    List<Vallisusdt> rolling10MinAll = combineService.calculateRolling10Min(oneMinList);
                    List<Vallisusdt> rolling30MinAll = combineService.calculateRolling30Min(oneMinList);
                    List<Vallisusdt> rolling60MinAll = combineService.calculateRolling60Min(oneMinList);
                    result.put("rolling_10min", rolling10MinAll);
                    result.put("rolling_30min", rolling30MinAll);
                    result.put("rolling_60min", rolling60MinAll);
                    result.put("10min_count", rolling10MinAll.size());
                    result.put("30min_count", rolling30MinAll.size());
                    result.put("60min_count", rolling60MinAll.size());
                    break;

                default:
                    return AjaxResult.error("不支持的时间框架");
            }

            klineCacheService.put(cacheKey, result);
            
            log.info("聚合数据返回: {}, 总耗时: {}ms", result.keySet(), System.currentTimeMillis() - startTime);
            return AjaxResult.success(result);
        } catch (Exception e) {
            log.error("获取聚合数据异常", e);
            return AjaxResult.error("获取聚合数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取BTC聚合K线数据
     */
    @GetMapping("/btc/data/aggregated")
    public AjaxResult getBtcAggregatedKlineData(@RequestParam(defaultValue = "1000") int oneMinLimit,
                                                @RequestParam String timeframe) {
        try {
            long startTime = System.currentTimeMillis();
            
            String cacheKey = String.format("btc_kline_%d_%s", oneMinLimit, timeframe);
            
            Map<String, Object> cachedData = klineCacheService.get(cacheKey);
            if (cachedData != null) {
                log.info("BTC缓存命中: {}, 耗时: {}ms", cacheKey, System.currentTimeMillis() - startTime);
                return AjaxResult.success(cachedData);
            }
            
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getBtc1minKline(oneMinLimit);
            log.info("获取BTC 1分钟数据: {}条, 耗时: {}ms", oneMinList.size(), System.currentTimeMillis() - startTime);

            Map<String, Object> result = new HashMap<>();
            result.put("one_minute_count", oneMinList.size());

            switch (timeframe) {
                case "10min":
                    List<Vallisusdt> rolling10Min = combineService.calculateRolling10Min(oneMinList);
                    result.put("rolling_10min", rolling10Min);
                    result.put("10min_count", rolling10Min.size());
                    break;

                case "30min":
                    List<Vallisusdt> rolling30Min = combineService.calculateRolling30Min(oneMinList);
                    result.put("rolling_30min", rolling30Min);
                    result.put("30min_count", rolling30Min.size());
                    break;

                case "60min":
                    List<Vallisusdt> rolling60Min = combineService.calculateRolling60Min(oneMinList);
                    result.put("rolling_60min", rolling60Min);
                    result.put("60min_count", rolling60Min.size());
                    break;

                case "all":
                    List<Vallisusdt> rolling10MinAll = combineService.calculateRolling10Min(oneMinList);
                    List<Vallisusdt> rolling30MinAll = combineService.calculateRolling30Min(oneMinList);
                    List<Vallisusdt> rolling60MinAll = combineService.calculateRolling60Min(oneMinList);
                    result.put("rolling_10min", rolling10MinAll);
                    result.put("rolling_30min", rolling30MinAll);
                    result.put("rolling_60min", rolling60MinAll);
                    result.put("10min_count", rolling10MinAll.size());
                    result.put("30min_count", rolling30MinAll.size());
                    result.put("60min_count", rolling60MinAll.size());
                    break;

                default:
                    return AjaxResult.error("不支持的时间框架");
            }

            klineCacheService.put(cacheKey, result);
            
            log.info("BTC聚合数据返回: {}, 总耗时: {}ms", result.keySet(), System.currentTimeMillis() - startTime);
            return AjaxResult.success(result);
        } catch (Exception e) {
            log.error("获取BTC聚合数据异常", e);
            return AjaxResult.error("获取BTC聚合数据失败: " + e.getMessage());
        }
    }

    /**
     * BTC价格预测统计分析
     * 分析当前价格与MA5/MA10/MA20的关系，以及5分钟后的价格走势
     */
    @GetMapping("/btc/statistics/prediction-analysis")
    public AjaxResult getBtcPredictionAnalysis(@RequestParam(defaultValue = "1000") int dataPoints) {
        try {
            log.info("开始BTC价格预测统计分析，数据点数: {}", dataPoints);
            
            // 获取1分钟K线数据（需要足够的数据点来计算MA和5分钟后的价格）
            // 需要额外25条数据：20条用于计算MA20，5条用于未来价格
            int requiredData = dataPoints + 25;
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getBtc1minKline(requiredData);
            
            log.info("获取到BTC数据: {}条，需要: {}条", oneMinList.size(), requiredData);
            
            if (oneMinList == null || oneMinList.size() < 30) {
                return AjaxResult.error("数据不足，无法进行分析。至少需要30条数据");
            }
            
            // 使用实际可用的数据点数量
            int actualDataPoints = Math.min(dataPoints, oneMinList.size() - 25);
            
            if (actualDataPoints < 5) {
                return AjaxResult.error("有效数据点不足，无法进行分析");
            }
            
            log.info("使用实际数据点数: {}", actualDataPoints);
            
            // 统计数据结构
            Map<String, Object> statistics = new HashMap<>();
            
            // MA5统计
            Map<String, Object> ma5Stats = analyzeMAPrediction(oneMinList, actualDataPoints, 5);
            statistics.put("MA5", ma5Stats);
            
            // MA10统计
            Map<String, Object> ma10Stats = analyzeMAPrediction(oneMinList, actualDataPoints, 10);
            statistics.put("MA10", ma10Stats);
            
            // MA20统计
            Map<String, Object> ma20Stats = analyzeMAPrediction(oneMinList, actualDataPoints, 20);
            statistics.put("MA20", ma20Stats);
            
            // 综合分析
            Map<String, Object> combinedStats = analyzeCombinedMAPrediction(oneMinList, actualDataPoints);
            statistics.put("combined", combinedStats);
            
            statistics.put("totalSamples", actualDataPoints);
            statistics.put("actualDataUsed", oneMinList.size());
            statistics.put("analysisTime", new Date());
            
            log.info("BTC价格预测统计分析完成，样本数: {}", actualDataPoints);
            return AjaxResult.success(statistics);
            
        } catch (Exception e) {
            log.error("BTC价格预测统计分析异常", e);
            return AjaxResult.error("分析失败: " + e.getMessage());
        }
    }
    
    /**
     * 分析单个MA指标的预测能力
     */
    private Map<String, Object> analyzeMAPrediction(List<Vallisusdt> data, int dataPoints, int maPeriod) {
        Map<String, Object> stats = new HashMap<>();
        
        int totalSamples = 0;
        int priceAboveMA = 0;  // 当前价格高于MA
        int priceBelowMA = 0;  // 当前价格低于MA
        
        // 当价格高于MA时，5分钟后的情况
        int aboveMAAndPriceUp = 0;   // 5分钟后价格上涨
        int aboveMAAndPriceDown = 0; // 5分钟后价格下跌
        int aboveMAAndPriceFlat = 0; // 5分钟后价格持平
        
        // 当价格低于MA时，5分钟后的情况
        int belowMAAndPriceUp = 0;
        int belowMAAndPriceDown = 0;
        int belowMAAndPriceFlat = 0;
        
        // 按偏离幅度分组的统计
        Map<String, int[]> deviationGroups = new HashMap<>();
        // 每个数组: [count, upCount, downCount, flatCount]
        deviationGroups.put("slight_above_0_1", new int[4]);      // 高于MA 0-1%
        deviationGroups.put("moderate_above_1_3", new int[4]);    // 高于MA 1-3%
        deviationGroups.put("significant_above_3_5", new int[4]); // 高于MA 3-5%
        deviationGroups.put("large_above_5", new int[4]);         // 高于MA >5%
        
        deviationGroups.put("slight_below_0_1", new int[4]);      // 低于MA 0-1%
        deviationGroups.put("moderate_below_1_3", new int[4]);    // 低于MA 1-3%
        deviationGroups.put("significant_below_3_5", new int[4]); // 低于MA 3-5%
        deviationGroups.put("large_below_5", new int[4]);         // 低于MA >5%
        
        // 计算MA值并统计
        for (int i = maPeriod; i < dataPoints; i++) {
            if (i + 5 >= data.size()) {
                break; // 确保有足够的未来数据
            }
            
            // 计算MA
            double sum = 0;
            for (int j = i - maPeriod; j < i; j++) {
                sum += Double.parseDouble(data.get(j).getEndPrice());
            }
            double ma = sum / maPeriod;
            
            double currentPrice = Double.parseDouble(data.get(i).getEndPrice());
            double futurePrice = Double.parseDouble(data.get(i + 5).getEndPrice());
            
            double priceChange = ((futurePrice - currentPrice) / currentPrice) * 100;
            double deviationPercent = ((currentPrice - ma) / ma) * 100; // 偏离MA的百分比
            
            totalSamples++;
            
            if (currentPrice > ma) {
                priceAboveMA++;
                
                if (priceChange > 0.01) { // 上涨超过0.01%
                    aboveMAAndPriceUp++;
                } else if (priceChange < -0.01) { // 下跌超过0.01%
                    aboveMAAndPriceDown++;
                } else {
                    aboveMAAndPriceFlat++;
                }
                
                // 按偏离幅度分组统计
                String groupKey;
                if (deviationPercent <= 1.0) {
                    groupKey = "slight_above_0_1";
                } else if (deviationPercent <= 3.0) {
                    groupKey = "moderate_above_1_3";
                } else if (deviationPercent <= 5.0) {
                    groupKey = "significant_above_3_5";
                } else {
                    groupKey = "large_above_5";
                }
                
                int[] groupStats = deviationGroups.get(groupKey);
                groupStats[0]++; // count
                if (priceChange > 0.01) {
                    groupStats[1]++; // upCount
                } else if (priceChange < -0.01) {
                    groupStats[2]++; // downCount
                } else {
                    groupStats[3]++; // flatCount
                }
                
            } else if (currentPrice < ma) {
                priceBelowMA++;
                
                if (priceChange > 0.01) {
                    belowMAAndPriceUp++;
                } else if (priceChange < -0.01) {
                    belowMAAndPriceDown++;
                } else {
                    belowMAAndPriceFlat++;
                }
                
                // 按偏离幅度分组统计
                String groupKey;
                double absDeviation = Math.abs(deviationPercent);
                if (absDeviation <= 1.0) {
                    groupKey = "slight_below_0_1";
                } else if (absDeviation <= 3.0) {
                    groupKey = "moderate_below_1_3";
                } else if (absDeviation <= 5.0) {
                    groupKey = "significant_below_3_5";
                } else {
                    groupKey = "large_below_5";
                }
                
                int[] groupStats = deviationGroups.get(groupKey);
                groupStats[0]++; // count
                if (priceChange > 0.01) {
                    groupStats[1]++; // upCount
                } else if (priceChange < -0.01) {
                    groupStats[2]++; // downCount
                } else {
                    groupStats[3]++; // flatCount
                }
            }
        }
        
        // 计算百分比
        stats.put("totalSamples", totalSamples);
        stats.put("priceAboveMACount", priceAboveMA);
        stats.put("priceBelowMACount", priceBelowMA);
        stats.put("priceAboveMAPercent", totalSamples > 0 ? (double) priceAboveMA / totalSamples * 100 : 0);
        stats.put("priceBelowMAPercent", totalSamples > 0 ? (double) priceBelowMA / totalSamples * 100 : 0);
        
        // 价格高于MA时的预测准确率
        if (priceAboveMA > 0) {
            Map<String, Object> aboveStats = new HashMap<>();
            aboveStats.put("upCount", aboveMAAndPriceUp);
            aboveStats.put("downCount", aboveMAAndPriceDown);
            aboveStats.put("flatCount", aboveMAAndPriceFlat);
            aboveStats.put("upPercent", (double) aboveMAAndPriceUp / priceAboveMA * 100);
            aboveStats.put("downPercent", (double) aboveMAAndPriceDown / priceAboveMA * 100);
            aboveStats.put("flatPercent", (double) aboveMAAndPriceFlat / priceAboveMA * 100);
            stats.put("whenPriceAboveMA", aboveStats);
        }
        
        // 价格低于MA时的预测准确率
        if (priceBelowMA > 0) {
            Map<String, Object> belowStats = new HashMap<>();
            belowStats.put("upCount", belowMAAndPriceUp);
            belowStats.put("downCount", belowMAAndPriceDown);
            belowStats.put("flatCount", belowMAAndPriceFlat);
            belowStats.put("upPercent", (double) belowMAAndPriceUp / priceBelowMA * 100);
            belowStats.put("downPercent", (double) belowMAAndPriceDown / priceBelowMA * 100);
            belowStats.put("flatPercent", (double) belowMAAndPriceFlat / priceBelowMA * 100);
            stats.put("whenPriceBelowMA", belowStats);
        }
        
        // 添加偏离幅度分析
        Map<String, Object> deviationAnalysis = new HashMap<>();
        
        for (Map.Entry<String, int[]> entry : deviationGroups.entrySet()) {
            String key = entry.getKey();
            int[] values = entry.getValue();
            
            if (values[0] > 0) { // 只有当有样本时才添加
                Map<String, Object> groupData = new HashMap<>();
                groupData.put("count", values[0]);
                groupData.put("upCount", values[1]);
                groupData.put("downCount", values[2]);
                groupData.put("flatCount", values[3]);
                groupData.put("upPercent", (double) values[1] / values[0] * 100);
                groupData.put("downPercent", (double) values[2] / values[0] * 100);
                groupData.put("flatPercent", (double) values[3] / values[0] * 100);
                
                deviationAnalysis.put(key, groupData);
            }
        }
        
        stats.put("deviationAnalysis", deviationAnalysis);
        
        return stats;
    }
    
    /**
     * 综合分析多个MA指标
     */
    private Map<String, Object> analyzeCombinedMAPrediction(List<Vallisusdt> data, int dataPoints) {
        Map<String, Object> stats = new HashMap<>();
        
        int totalSamples = 0;
        
        // 三种MA都高于价格的场景
        int allAboveScenario = 0;
        int allAboveAndPriceUp = 0;
        int allAboveAndPriceDown = 0;
        
        // 三种MA都低于价格的场景
        int allBelowScenario = 0;
        int allBelowAndPriceUp = 0;
        int allBelowAndPriceDown = 0;
        
        // 价格在MA5和MA10之间
        int between5and10Scenario = 0;
        int between5and10AndPriceUp = 0;
        int between5and10AndPriceDown = 0;
        
        // 价格在MA10和MA20之间
        int between10and20Scenario = 0;
        int between10and20AndPriceUp = 0;
        int between10and20AndPriceDown = 0;
        
        for (int i = 20; i < dataPoints; i++) {
            if (i + 5 >= data.size()) {
                break; // 确保有足够的未来数据
            }
            
            // 计算三个MA
            double ma5 = calculateMA(data, i, 5);
            double ma10 = calculateMA(data, i, 10);
            double ma20 = calculateMA(data, i, 20);
            
            double currentPrice = Double.parseDouble(data.get(i).getEndPrice());
            double futurePrice = Double.parseDouble(data.get(i + 5).getEndPrice());
            double priceChange = ((futurePrice - currentPrice) / currentPrice) * 100;
            
            totalSamples++;
            
            // 判断场景
            if (currentPrice > ma5 && currentPrice > ma10 && currentPrice > ma20) {
                // 价格高于所有MA
                allAboveScenario++;
                if (priceChange > 0.01) {
                    allAboveAndPriceUp++;
                } else if (priceChange < -0.01) {
                    allAboveAndPriceDown++;
                }
            } else if (currentPrice < ma5 && currentPrice < ma10 && currentPrice < ma20) {
                // 价格低于所有MA
                allBelowScenario++;
                if (priceChange > 0.01) {
                    allBelowAndPriceUp++;
                } else if (priceChange < -0.01) {
                    allBelowAndPriceDown++;
                }
            } else if (currentPrice > ma5 && currentPrice < ma10) {
                // 价格在MA5和MA10之间
                between5and10Scenario++;
                if (priceChange > 0.01) {
                    between5and10AndPriceUp++;
                } else if (priceChange < -0.01) {
                    between5and10AndPriceDown++;
                }
            } else if (currentPrice > ma10 && currentPrice < ma20) {
                // 价格在MA10和MA20之间
                between10and20Scenario++;
                if (priceChange > 0.01) {
                    between10and20AndPriceUp++;
                } else if (priceChange < -0.01) {
                    between10and20AndPriceDown++;
                }
            }
        }
        
        // 统计结果
        Map<String, Object> scenario1 = new HashMap<>();
        scenario1.put("count", allAboveScenario);
        scenario1.put("upPercent", allAboveScenario > 0 ? (double) allAboveAndPriceUp / allAboveScenario * 100 : 0);
        scenario1.put("downPercent", allAboveScenario > 0 ? (double) allAboveAndPriceDown / allAboveScenario * 100 : 0);
        stats.put("priceAboveAllMA", scenario1);
        
        Map<String, Object> scenario2 = new HashMap<>();
        scenario2.put("count", allBelowScenario);
        scenario2.put("upPercent", allBelowScenario > 0 ? (double) allBelowAndPriceUp / allBelowScenario * 100 : 0);
        scenario2.put("downPercent", allBelowScenario > 0 ? (double) allBelowAndPriceDown / allBelowScenario * 100 : 0);
        stats.put("priceBelowAllMA", scenario2);
        
        Map<String, Object> scenario3 = new HashMap<>();
        scenario3.put("count", between5and10Scenario);
        scenario3.put("upPercent", between5and10Scenario > 0 ? (double) between5and10AndPriceUp / between5and10Scenario * 100 : 0);
        scenario3.put("downPercent", between5and10Scenario > 0 ? (double) between5and10AndPriceDown / between5and10Scenario * 100 : 0);
        stats.put("priceBetweenMA5andMA10", scenario3);
        
        Map<String, Object> scenario4 = new HashMap<>();
        scenario4.put("count", between10and20Scenario);
        scenario4.put("upPercent", between10and20Scenario > 0 ? (double) between10and20AndPriceUp / between10and20Scenario * 100 : 0);
        scenario4.put("downPercent", between10and20Scenario > 0 ? (double) between10and20AndPriceDown / between10and20Scenario * 100 : 0);
        stats.put("priceBetweenMA10andMA20", scenario4);
        
        stats.put("totalSamples", totalSamples);
        
        return stats;
    }
    
    /**
     * 计算指定位置的MA值
     */
    private double calculateMA(List<Vallisusdt> data, int currentIndex, int period) {
        double sum = 0;
        for (int j = currentIndex - period; j < currentIndex; j++) {
            sum += Double.parseDouble(data.get(j).getEndPrice());
        }
        return sum / period;
    }
    
    /**
     * BTC实时预测（基于最新数据）
     */
    @GetMapping("/btc/feature/realtime-predict")
    public AjaxResult realtimePredict() {
        try {
            log.info("开始BTC实时预测...");
            
            // 获取最新的K线数据
            List<Vallisusdt> klineData = vallisUsdtEventTool.getBtc1minKline(50);
            
            if (klineData == null || klineData.size() < 30) {
                return AjaxResult.error("数据不足");
            }
            
            // 提取最新特征
            List<BtcFeatureEngineeringService.FeatureData> features = 
                featureEngineeringService.extractFeatures(klineData, 5);
            
            if (features == null || features.isEmpty()) {
                return AjaxResult.error("无法提取特征");
            }
            
            // 获取最新时刻的特征
            BtcFeatureEngineeringService.FeatureData latestFeature = features.get(features.size() - 1);
            
            // 生成所有预测
            Map<String, Object> predictions = new HashMap<>();
            predictions.put("timestamp", latestFeature.getTimestamp());
            predictions.put("currentPrice", Double.parseDouble(klineData.get(klineData.size() - 1).getEndPrice()));
            
            // 单因子预测
            Map<String, Object> singlePredictions = new HashMap<>();
            singlePredictions.put("inertia", featureEngineeringService.predictByInertia(latestFeature));
            singlePredictions.put("position", featureEngineeringService.predictByPosition(latestFeature));
            singlePredictions.put("ma", featureEngineeringService.predictByMA(latestFeature));
            singlePredictions.put("rsi", featureEngineeringService.predictByRSI(latestFeature));
            singlePredictions.put("volume", featureEngineeringService.predictByVolume(latestFeature));
            predictions.put("singleFactors", singlePredictions);
            
            // 组合策略预测
            Map<String, Object> comboPredictions = new HashMap<>();
            comboPredictions.put("combo1_technical", featureEngineeringService.predictCombo1(latestFeature));
            comboPredictions.put("combo2_micro", featureEngineeringService.predictCombo2(latestFeature));
            comboPredictions.put("combo3_strong", featureEngineeringService.predictCombo3(latestFeature));
            comboPredictions.put("combo4_trend", featureEngineeringService.predictTrendFollowing(latestFeature));
            comboPredictions.put("combo5_reversion", featureEngineeringService.predictMeanReversion(latestFeature));
            comboPredictions.put("combo6_momentum", featureEngineeringService.predictMomentumBreakout(latestFeature));
            comboPredictions.put("combo7_resonance", featureEngineeringService.predictMultiResonance(latestFeature));
            predictions.put("combos", comboPredictions);

            // 综合建议
            String recommendation = generateRecommendation(comboPredictions);
            predictions.put("recommendation", recommendation);
            
            // 当前特征值
            Map<String, Object> currentFeatures = new HashMap<>();
            currentFeatures.put("rsi", String.format("%.2f", latestFeature.getRsi14()));
            currentFeatures.put("ema9", String.format("%.2f", latestFeature.getEma9()));
            currentFeatures.put("ema21", String.format("%.2f", latestFeature.getEma21()));
            currentFeatures.put("volRatio", latestFeature.getVolRatio5m() != null ? 
                String.format("%.2f", latestFeature.getVolRatio5m()) : "N/A");
            currentFeatures.put("position5m", String.format("%.2f", latestFeature.getPosition5m()));
            predictions.put("currentFeatures", currentFeatures);
            
            return AjaxResult.success(predictions);
            
        } catch (Exception e) {
            log.error("BTC实时预测异常", e);
            return AjaxResult.error("预测失败: " + e.getMessage());
        }
    }
    
    /**
     * 生成综合建议
     */
    private String generateRecommendation(Map<String, Object> comboPredictions) {
        int bullishCount = 0;
        int bearishCount = 0;
        int validCount = 0;
        
        for (Object pred : comboPredictions.values()) {
            if (pred == null) continue;
            
            validCount++;
            Integer prediction = (Integer) pred;
            if (prediction == 1) {
                bullishCount++;
            } else if (prediction == 0) {
                bearishCount++;
            }
        }
        
        if (validCount == 0) {
            return "观望 - 无明确信号";
        }
        
        double bullishRatio = (double) bullishCount / validCount;
        double bearishRatio = (double) bearishCount / validCount;
        
        if (bullishRatio >= 0.7) {
            return "强烈看多 - " + bullishCount + "/" + validCount + "个策略看涨";
        } else if (bullishRatio >= 0.5) {
            return "温和看多 - " + bullishCount + "/" + validCount + "个策略看涨";
        } else if (bearishRatio >= 0.7) {
            return "强烈看空 - " + bearishCount + "/" + validCount + "个策略看跌";
        } else if (bearishRatio >= 0.5) {
            return "温和看空 - " + bearishCount + "/" + validCount + "个策略看跌";
        } else {
            return "震荡观望 - 多空分歧";
        }
    }

}
