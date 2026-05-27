package com.alphay.boot.web.controller.vallix;


import com.alphay.boot.common.core.controller.BaseController;
import com.alphay.boot.common.core.domain.AjaxResult;
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
     * 获取聚合K线数据
     */
    @GetMapping("/data/aggregated")
    public AjaxResult getAggregatedKlineData(@RequestParam(defaultValue = "1000") int oneMinLimit,
                                             @RequestParam String timeframe) {
        try {
            List<Vallisusdt> oneMinList = vallisUsdtEventTool.getEth1minKline(oneMinLimit);

            Map<String, Object> result = new HashMap<>();
            result.put("one_minute_count", oneMinList.size());

            switch (timeframe) {
                case "10min":
                    List<Vallisusdt> rolling10Min = combineService.calculateRolling10Min(oneMinList);
                    List<Vallisusdt> natural10Min = combineService.buildNatural10MinList(oneMinList);
                    result.put("rolling_10min", rolling10Min);
                    result.put("natural_10min", natural10Min);
                    result.put("10min_count", rolling10Min.size());
                    break;

                case "30min":
                    List<Vallisusdt> rolling30Min = combineService.calculateRolling30Min(oneMinList);
                    List<Vallisusdt> natural30Min = combineService.buildNatural10MinList(oneMinList);
                    result.put("rolling_30min", rolling30Min);
                    result.put("natural_30min", natural30Min);
                    result.put("30min_count", rolling30Min.size());
                    break;

                case "all":
                    List<Vallisusdt> rolling10MinAll = combineService.calculateRolling10Min(oneMinList);
                    List<Vallisusdt> rolling30MinAll = combineService.calculateRolling30Min(oneMinList);
                    result.put("rolling_10min", rolling10MinAll);
                    result.put("rolling_30min", rolling30MinAll);
                    result.put("10min_count", rolling10MinAll.size());
                    result.put("30min_count", rolling30MinAll.size());
                    break;

                default:
                    return AjaxResult.error("时间框架参数错误，必须是'10min'、'30min'或'all'");
            }

            return AjaxResult.success(result);
        } catch (Exception e) {
            return AjaxResult.error("获取聚合数据失败: " + e.getMessage());
        }
    }


}
