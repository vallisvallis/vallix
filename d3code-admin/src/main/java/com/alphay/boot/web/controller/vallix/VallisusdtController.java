package com.alphay.boot.web.controller.vallix;

import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.alphay.boot.bpm.service.impl.IVallisusdtService;
import com.alphay.boot.common.core.controller.BaseController;
import com.alphay.boot.common.core.domain.AjaxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BTC/USDT K线数据 Controller
 * 提供BTC历史数据获取、存储和查询接口
 *
 * @author d3code
 * @date 2025-05-26
 */
@RestController
@Slf4j
@RequestMapping("/api/vallisusdt")
public class VallisusdtController extends BaseController {

    @Autowired
    private VallisUsdtEventTool vallisUsdtEventTool;

    @Autowired
    private IVallisusdtService vallisusdtService;

    /**
     * 获取币安 BTC/USDT 最新K线数据
     * GET /api/vallisusdt/btc/latest
     */
    @GetMapping("/btc/latest")
    public AjaxResult getLatestBtcData() {
        try {
            Vallisusdt data = vallisUsdtEventTool.getLatestBtcData();
            if (data != null) {
                return AjaxResult.success("获取成功", data);
            } else {
                return AjaxResult.error("获取失败，数据为空");
            }
        } catch (Exception e) {
            log.error("获取BTC最新数据异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取币安 BTC/USDT 历史K线数据
     * GET /api/vallisusdt/btc/history?limit=100
     * @param limit 获取数量（最大1000）
     */
    @GetMapping("/btc/history")
    public AjaxResult getBtcHistoricalData(@RequestParam(defaultValue = "100") int limit) {
        try {
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcHistoricalData(Math.min(limit, 1000));
            return AjaxResult.success("获取成功", dataList);
        } catch (Exception e) {
            log.error("获取BTC历史数据异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取币安 BTC/USDT 1分钟K线数据
     * GET /api/vallisusdt/btc/kline?limit=50
     */
    @GetMapping("/btc/kline")
    public AjaxResult getBtcKline(@RequestParam(defaultValue = "50") int limit) {
        try {
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtc1minKline(Math.min(limit, 1000));
            return AjaxResult.success("获取成功", dataList);
        } catch (Exception e) {
            log.error("获取BTC K线数据异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 拉取并保存BTC历史数据到数据库
     * POST /api/vallisusdt/btc/save
     * @param limit 获取并保存的数量
     */
    @PostMapping("/btc/save")
    public AjaxResult saveBtcData(@RequestParam(defaultValue = "100") int limit) {
        try {
            log.info("开始拉取BTC历史数据，数量: {}", limit);
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcHistoricalData(Math.min(limit, 1000));

            if (dataList.isEmpty()) {
                return AjaxResult.error("没有获取到数据");
            }

            // 批量保存到数据库
            int successCount = 0;
            for (Vallisusdt data : dataList) {
                try {
                    vallisusdtService.save(data);
                    successCount++;
                } catch (Exception e) {
                    log.warn("保存单条数据失败: {}", e.getMessage());
                }
            }

            log.info("BTC历史数据保存完成，成功: {}/{}条", successCount, dataList.size());
            return AjaxResult.success("保存成功，共保存 " + successCount + " 条数据");

        } catch (Exception e) {
            log.error("保存BTC数据异常", e);
            return AjaxResult.error("保存失败: " + e.getMessage());
        }
    }

    /**
     * 查询数据库中的BTC数据
     * GET /api/vallisusdt/btc/query
     */
    @GetMapping("/btc/query")
    public AjaxResult queryBtcData() {
        try {
            List<Vallisusdt> dataList = vallisusdtService.list();
            return AjaxResult.success("查询成功", dataList);
        } catch (Exception e) {
            log.error("查询BTC数据异常", e);
            return AjaxResult.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 查询数据库中最新的N条BTC数据
     * GET /api/vallisusdt/btc/query/latest?limit=10
     */
    @GetMapping("/btc/query/latest")
    public AjaxResult queryLatestBtcData(@RequestParam(defaultValue = "10") int limit) {
        try {
            List<Vallisusdt> dataList = vallisusdtService.list();
            if (dataList.size() <= limit) {
                return AjaxResult.success("查询成功", dataList);
            }
            // 返回最新的N条（假设按ID倒序）
            List<Vallisusdt> latestList = dataList.subList(dataList.size() - limit, dataList.size());
            return AjaxResult.success("查询成功", latestList);
        } catch (Exception e) {
            log.error("查询BTC最新数据异常", e);
            return AjaxResult.error("查询失败: " + e.getMessage());
        }
    }

    /**
     * 清空数据库中的BTC数据
     * DELETE /api/vallisusdt/btc/clear
     */
    @DeleteMapping("/btc/clear")
    public AjaxResult clearBtcData() {
        try {
            vallisusdtService.remove(null);
            log.info("BTC数据已清空");
            return AjaxResult.success("清空成功");
        } catch (Exception e) {
            log.error("清空BTC数据异常", e);
            return AjaxResult.error("清空失败: " + e.getMessage());
        }
    }

    /**
     * 获取BTC近一年的分钟数据
     * GET /api/vallisusdt/btc/year
     */
    @GetMapping("/btc/year")
    public AjaxResult getBtcYearData() {
        try {
            log.info("开始获取BTC近一年分钟数据");
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcYearMinuteData();
            log.info("BTC近一年数据获取完成，共{}条", dataList.size());
            return AjaxResult.success("获取成功，共 " + dataList.size() + " 条数据", dataList);
        } catch (Exception e) {
            log.error("获取BTC近一年数据异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取BTC指定天数的分钟数据
     * GET /api/vallisusdt/btc/days?days=30
     */
    @GetMapping("/btc/days")
    public AjaxResult getBtcDataByDays(@RequestParam(defaultValue = "30") int days) {
        try {
            log.info("开始获取BTC {}天分钟数据", days);
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcHistoricalDataByDays(days);
            log.info("BTC {}天数据获取完成，共{}条", days, dataList.size());
            return AjaxResult.success("获取成功，共 " + dataList.size() + " 条数据", dataList);
        } catch (Exception e) {
            log.error("获取BTC历史数据异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取BTC指定时间范围的分钟数据
     * GET /api/vallisusdt/btc/range?startTime=xxx&endTime=xxx
     */
    @GetMapping("/btc/range")
    public AjaxResult getBtcDataByTimeRange(
            @RequestParam long startTime,
            @RequestParam long endTime) {
        try {
            log.info("开始获取BTC时间范围数据: {} ~ {}", startTime, endTime);
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcHistoricalDataByTimeRange(startTime, endTime);
            log.info("BTC时间范围数据获取完成，共{}条", dataList.size());
            return AjaxResult.success("获取成功，共 " + dataList.size() + " 条数据", dataList);
        } catch (Exception e) {
            log.error("获取BTC时间范围数据异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取BTC近一年数据并保存到数据库
     * POST /api/vallisusdt/btc/save/year
     */
    @PostMapping("/btc/save/year")
    public AjaxResult saveBtcYearData() {
        try {
            log.info("开始获取并保存BTC近一年分钟数据");
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcYearMinuteData();
            
            if (dataList.isEmpty()) {
                return AjaxResult.error("没有获取到数据");
            }
            
            log.info("开始保存{}条BTC数据到数据库（批量插入）", dataList.size());
            
            // 批量插入，每1000条提交一次
            int batchSize = 1000;
            int totalSuccess = 0;
            int totalFail = 0;
            
            for (int i = 0; i < dataList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dataList.size());
                List<Vallisusdt> batchList = dataList.subList(i, end);
                
                try {
                    vallisusdtService.saveBatch(batchList);
                    totalSuccess += batchList.size();
                } catch (Exception e) {
                    totalFail += batchList.size();
                    log.warn("批量保存失败: {}", e.getMessage());
                }
                
                // 每保存10000条输出一次进度
                if ((i + batchSize) % 10000 == 0 || end == dataList.size()) {
                    log.info("BTC数据保存进度: {}/{} 条 ({}%)", 
                        end, dataList.size(), 
                        String.format("%.1f", end * 100.0 / dataList.size()));
                }
            }
            
            log.info("BTC近一年数据保存完成，成功: {} 条，失败: {} 条", totalSuccess, totalFail);
            return AjaxResult.success(
                String.format("保存完成，成功 %d 条，失败 %d 条", totalSuccess, totalFail),
                getResultMap(totalSuccess, totalFail)
            );
            
        } catch (Exception e) {
            log.error("保存BTC近一年数据异常", e);
            return AjaxResult.error("保存失败: " + e.getMessage());
        }
    }

    /**
     * 获取BTC指定天数数据并保存到数据库
     * POST /api/vallisusdt/btc/save/days?days=30
     */
    @PostMapping("/btc/save/days")
    public AjaxResult saveBtcDataByDays(@RequestParam(defaultValue = "30") int days) {
        try {
            log.info("开始获取并保存BTC {}天分钟数据", days);
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcHistoricalDataByDays(days);
            
            if (dataList.isEmpty()) {
                return AjaxResult.error("没有获取到数据");
            }
            
            log.info("开始保存{}条BTC数据到数据库（批量插入）", dataList.size());
            
            // 批量插入，每1000条提交一次
            int batchSize = 1000;
            int totalSuccess = 0;
            int totalFail = 0;
            
            for (int i = 0; i < dataList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dataList.size());
                List<Vallisusdt> batchList = dataList.subList(i, end);
                
                try {
                    vallisusdtService.saveBatch(batchList);
                    totalSuccess += batchList.size();
                } catch (Exception e) {
                    totalFail += batchList.size();
                    log.warn("批量保存失败: {}", e.getMessage());
                }
                
                if (end == dataList.size()) {
                    log.info("BTC数据保存进度: {}/{} 条", end, dataList.size());
                }
            }
            
            log.info("BTC {}天数据保存完成，成功: {} 条，失败: {} 条", days, totalSuccess, totalFail);
            return AjaxResult.success(
                String.format("保存完成，成功 %d 条，失败 %d 条", totalSuccess, totalFail),
                getResultMap(totalSuccess, totalFail)
            );
            
        } catch (Exception e) {
            log.error("保存BTC数据异常", e);
            return AjaxResult.error("保存失败: " + e.getMessage());
        }
    }

    /**
     * 创建结果Map（Java 1.8兼容）
     */
    private Map<String, Object> getResultMap(int successCount, int failCount) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("successCount", successCount);
        resultMap.put("failCount", failCount);
        return resultMap;
    }
}