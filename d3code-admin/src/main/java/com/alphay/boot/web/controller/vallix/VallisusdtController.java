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
import java.util.stream.Collectors;

/**
 * BTC/USDT K线数据 Controller
 * 提供BTC历史数据获取、存储和查询接口
 *
 * @author d3code
 * @date 2025-05-26
 */
@RestController
@Slf4j
@RequestMapping("/system/vallisusdt")
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
     * GET /system/vallisusdt/btc/kline?limit=50
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

    // 修改 getRawKlineData 方法
    // 修改 VallisusdtController.java 中的 getRawKlineData 方法
    @GetMapping("/data/raw")
    public AjaxResult getRawKlineData(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "1m") String interval) {
        try {
            log.info("获取ETH原始K线数据, limit={}, interval={}", limit, interval);

            List<Vallisusdt> dataList;

            // 特殊处理10分钟间隔（币安API不支持）
            if ("10m".equals(interval)) {
                dataList = vallisUsdtEventTool.getEth10minKline(Math.min(limit, 30));
            } else {
                dataList = vallisUsdtEventTool.getEthKline(Math.min(limit, 1000), interval);
            }

            // 转换为前端期望的字段格式
            List<KlineDataDTO> dtoList = dataList.stream()
                    .map(this::convertToKlineDataDTO)
                    .collect(java.util.stream.Collectors.toList());

            return AjaxResult.success("获取成功", dtoList);
        } catch (Exception e) {
            log.error("获取ETH原始K线数据异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }
    
    /**
     * 将Vallisusdt实体转换为前端期望的KlineDataDTO格式
     */
    private KlineDataDTO convertToKlineDataDTO(Vallisusdt data) {
        return KlineDataDTO.builder()
                .startPrice(parseDouble(data.getOpen()))
                .endPrice(parseDouble(data.getClose()))
                .maxPrice(parseDouble(data.getHigh()))
                .minPrice(parseDouble(data.getLow()))
                .calcCount(parseDouble(data.getVolume()))
                .startTime(data.getOpenTime())
                .endTime(data.getCloseTime())
                .build();
    }

    /**
     * 安全解析Double值
     */
    private Double parseDouble(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 获取BTC原始K线数据
     * GET /system/vallisusdt/btc/data/raw?limit=100&interval=1m
     * @param limit 数据条数，默认100，最大1000
     * @param interval 时间间隔，默认1m（支持1m, 5m, 15m, 30m, 1h等）
     */
    @GetMapping("/btc/data/raw")
    public AjaxResult getBtcRawKlineData(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "1m") String interval) {
        try {
            log.info("获取BTC原始K线数据, limit={}, interval={}", limit, interval);
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtc1minKline(Math.min(limit, 1000));
            return AjaxResult.success("获取成功", dataList);
        } catch (Exception e) {
            log.error("获取BTC原始K线数据异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 拉取并保存BTC历史数据到数据库
     * POST /system/vallisusdt/btc/save
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

    /**
     * 获取WebSocket连接状态
     * GET /system/vallisusdt/ws/status
     */
    @GetMapping("/ws/status")
    public AjaxResult getWsStatus() {
        try {
            Map<String, Object> status = new HashMap<>();
            
            // ETH K线WebSocket状态
            int ethOnlineCount = com.alphay.boot.web.websocket.EthKlineWebSocketHandler.getOnlineCount();
            status.put("ethKlineWsOnline", ethOnlineCount);
            status.put("ethKlineWsEndpoint", "/system/vallisusdt/ws/kline");
            
            // BTC WebSocket状态
            int btcOnlineCount = com.alphay.boot.web.websocket.BtcRealTimeHandler.getOnlineCount();
            status.put("btcWsOnline", btcOnlineCount);
            status.put("btcWsEndpoint", "/ws/btc/realtime");
            
            return AjaxResult.success("WebSocket状态查询成功", status);
        } catch (Exception e) {
            log.error("获取WebSocket状态异常", e);
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 测试WebSocket连接
     * GET /system/vallisusdt/ws/test
     */
    @GetMapping("/ws/test")
    public AjaxResult testWsConnection() {
        try {
            // 测试币安API是否可访问
            Vallisusdt testData = vallisUsdtEventTool.getLatestBtcData();
            boolean apiAvailable = testData != null;
            
            Map<String, Object> result = new HashMap<>();
            result.put("binanceApiAvailable", apiAvailable);
            result.put("testData", testData);
            result.put("ethWsEndpoint", "ws://" + getServerHost() + "/system/vallisusdt/ws/kline");
            result.put("btcWsEndpoint", "ws://" + getServerHost() + "/ws/btc/realtime");
            
            if (apiAvailable) {
                return AjaxResult.success("WebSocket服务正常", result);
            } else {
                return AjaxResult.warn("币安API不可访问，请检查网络", result);
            }
        } catch (Exception e) {
            log.error("测试WebSocket异常", e);
            return AjaxResult.error("测试失败: " + e.getMessage());
        }
    }

    /**
     * 获取服务器主机名
     */
    private String getServerHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress() + ":8080";
        } catch (java.net.UnknownHostException e) {
            log.warn("无法获取本地主机地址: {}", e.getMessage());
            return "localhost:8080";
        }
    }
}