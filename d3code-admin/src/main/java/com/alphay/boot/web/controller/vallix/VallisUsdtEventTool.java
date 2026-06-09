package com.alphay.boot.web.controller.vallix;

import com.alphay.boot.bpm.api.domain.Vallisusdt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
public class VallisUsdtEventTool {
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 获取币安 ETH/USDT 1分钟K线
     * 接口返回数组对应：
     * [0] 开盘时间
     * [1] 开盘价
     * [2] 最高价
     * [3] 最低价
     * [4] 收盘价
     * [5] 成交量(ETH)
     * [6] 收盘时间
     * [7] 成交额(USDT)
     * [8] 成交笔数
     * [9] 主动买入成交量
     * [10] 主动买入成交额
     * [11] 忽略
     */
    public List<Vallisusdt> getEth1minKline(int limit) {
        String url = "https://data-api.binance.vision/api/v3/klines?symbol=ETHUSDT&interval=1m&limit=" + limit;
        try {
            Object[][] dataArray = restTemplate.getForObject(url, Object[][].class);

            if (dataArray == null || dataArray.length == 0) {
                return new ArrayList<>();
            }

            List<Vallisusdt> result = new ArrayList<>();
            for (Object[] arr : dataArray) {
                result.add(convertToVallisusdt(arr));
            }

            log.info("成功获取ETH K线数量：{}", result.size());
            return result;

        } catch (Exception e) {
            log.error("拉取币安ETH K线数据异常", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取币安 BTC/USDT 1分钟K线
     */
    public List<Vallisusdt> getBtc1minKline(int limit) {
        String url = "https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=" + limit;
        return fetchKlineData(url, "BTCUSDT");
    }

    /**
     * 获取币安 BTC/USDT 最新数据（支持秒级别实时数据模拟）
     * 使用 websocket 或最新K线数据来模拟秒级更新
     */
    public Vallisusdt getLatestBtcData() {
        List<Vallisusdt> klines = getBtc1minKline(1);
        if (!klines.isEmpty()) {
            Vallisusdt latest = klines.get(0);
            // 更新结束时间为当前时间（模拟秒级更新）
            latest.setCloseTime(System.currentTimeMillis());
            return latest;
        }
        return null;
    }

    /**
     * 获取币安 BTC/USDT 历史K线数据（批量获取）
     * @param limit 获取数量，最大1000
     */
    public List<Vallisusdt> getBtcHistoricalData(int limit) {
        return getBtc1minKline(Math.min(limit, 1000));
    }

    /**
     * 获取币安 BTC/USDT 近一年的分钟数据
     * 币安API每次最多返回1000条，需要分批获取
     * 一年约 365 * 24 * 60 = 525,600 条数据
     * @return 近一年的所有分钟K线数据
     */
    public List<Vallisusdt> getBtcYearMinuteData() {
        return getBtcHistoricalDataByDays(365);
    }

    /**
     * 获取币安 BTC/USDT 指定天数的分钟数据
     * @param days 天数
     * @return 指定天数的分钟K线数据
     */
    public List<Vallisusdt> getBtcHistoricalDataByDays(int days) {
        List<Vallisusdt> allData = new ArrayList<>();
        
        // 计算开始时间（days天前）
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (long) days * 24 * 60 * 60 * 1000L;
        
        log.info("开始获取BTC {}天历史数据，时间范围: {} ~ {}", days, startTime, endTime);
        
        // 每次获取1000条（币安API限制）
        int batchSize = 1000;
        long currentEndTime = endTime;
        int totalFetched = 0;
        int requestCount = 0;
        
        while (currentEndTime > startTime) {
            try {
                // 构建URL，使用endTime参数
                String url = String.format(
                    "https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=%d&endTime=%d",
                    batchSize, currentEndTime
                );
                
                Object[][] dataArray = restTemplate.getForObject(url, Object[][].class);
                
                if (dataArray == null || dataArray.length == 0) {
                    log.info("没有更多数据，结束获取");
                    break;
                }
                
                // 转换数据并添加到结果
                List<Vallisusdt> batchData = new ArrayList<>();
                for (Object[] arr : dataArray) {
                    batchData.add(convertToVallisusdt(arr));
                }
                
                // 添加到总结果（逆序，使时间从早到晚）
                allData.addAll(0, batchData);
                
                int fetched = batchData.size();
                totalFetched += fetched;
                requestCount++;
                
                log.info("第{}次请求，获取{}条数据，累计{}条", requestCount, fetched, totalFetched);
                
                // 更新endTime为当前批次最早的数据时间
                if (!batchData.isEmpty()) {
                    currentEndTime = batchData.get(0).getOpenTime() - 1; // 减1毫秒确保不重复
                }
                
                // 如果获取的数据少于batchSize，说明已经到最早的数据
                if (fetched < batchSize) {
                    break;
                }
                
                // 避免请求过快被限流
                Thread.sleep(100);
                
            } catch (Exception e) {
                log.error("批量获取BTC历史数据异常，已获取{}条", totalFetched, e);
                break;
            }
        }
        
        log.info("BTC历史数据获取完成，共获取{}条，请求{}次", totalFetched, requestCount);
        return allData;
    }

    /**
     * 获取币安 BTC/USDT 指定时间范围的分钟数据
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime 结束时间（毫秒时间戳）
     * @return 时间范围内的分钟K线数据
     */
    public List<Vallisusdt> getBtcHistoricalDataByTimeRange(long startTime, long endTime) {
        List<Vallisusdt> allData = new ArrayList<>();
        
        log.info("开始获取BTC历史数据，时间范围: {} ~ {}", startTime, endTime);
        
        int batchSize = 1000;
        long currentEndTime = endTime;
        int totalFetched = 0;
        int requestCount = 0;
        
        while (currentEndTime > startTime) {
            try {
                String url = String.format(
                    "https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=%d&endTime=%d",
                    batchSize, currentEndTime
                );
                
                Object[][] dataArray = restTemplate.getForObject(url, Object[][].class);
                
                if (dataArray == null || dataArray.length == 0) {
                    break;
                }
                
                List<Vallisusdt> batchData = new ArrayList<>();
                for (Object[] arr : dataArray) {
                    batchData.add(convertToVallisusdt(arr));
                }
                
                allData.addAll(0, batchData);
                
                int fetched = batchData.size();
                totalFetched += fetched;
                requestCount++;
                
                log.info("第{}次请求，获取{}条数据，累计{}条", requestCount, fetched, totalFetched);
                
                if (!batchData.isEmpty()) {
                    currentEndTime = batchData.get(0).getOpenTime() - 1;
                }
                
                if (fetched < batchSize) {
                    break;
                }
                
                Thread.sleep(100);
                
            } catch (Exception e) {
                log.error("获取BTC历史数据异常，已获取{}条", totalFetched, e);
                break;
            }
        }
        
        log.info("BTC历史数据获取完成，共获取{}条", totalFetched);
        return allData;
    }

    /**
     * 通用K线数据获取方法
     */
    private List<Vallisusdt> fetchKlineData(String url, String symbol) {
        try {
            Object[][] dataArray = restTemplate.getForObject(url, Object[][].class);

            if (dataArray == null || dataArray.length == 0) {
                log.warn("获取{} K线数据为空", symbol);
                return new ArrayList<>();
            }

            List<Vallisusdt> result = new ArrayList<>();
            for (Object[] arr : dataArray) {
                result.add(convertToVallisusdt(arr));
            }

            log.info("成功获取{} K线数量：{}", symbol, result.size());
            return result;

        } catch (Exception e) {
            log.error("拉取{} K线数据异常", symbol, e);
            return new ArrayList<>();
        }
    }

    /**
     * 币安数组 → 实体 Vallisusdt（兼容币安API格式）
     * 币安K线数组索引:
     * [0] openTime - K线开始时间
     * [1] open - 开盘价
     * [2] high - 最高价
     * [3] low - 最低价
     * [4] close - 收盘价
     * [5] volume - 成交量
     * [6] closeTime - K线结束时间
     * [7] quoteAssetVolume - 成交额
     * [8] numberOfTrades - 成交笔数
     * [9] takerBuyBaseAssetVolume - 主动买入成交量
     * [10] takerBuyQuoteAssetVolume - 主动买入成交额
     * [11] ignore
     */
    private Vallisusdt convertToVallisusdt(Object[] arr) {
        Vallisusdt vo = new Vallisusdt();
        try {
            // 币安API字段映射
            vo.setOpenTime(Long.parseLong(arr[0].toString()));    // [0] openTime
            vo.setOpen(arr[1].toString());                        // [1] open
            vo.setHigh(arr[2].toString());                        // [2] high
            vo.setLow(arr[3].toString());                         // [3] low
            vo.setClose(arr[4].toString());                       // [4] close
            vo.setVolume(arr[5].toString());                      // [5] volume
            vo.setCloseTime(Long.parseLong(arr[6].toString()));   // [6] closeTime
            vo.setQuoteVolume(arr[7].toString());                 // [7] quoteVolume
            
            vo.setTrades(Long.parseLong(arr[8].toString()));      // [8] trades
            vo.setTakerBuyBase(arr[9].toString());                // [9] takerBuyBase
            vo.setTakerBuyQuote(arr[10].toString());              // [10] takerBuyQuote

            vo.setCreateTime(new Date());
            vo.setUpdateTime(new Date());
        } catch (Exception e) {
            log.error("解析K线异常", e);
        }
        return vo;
    }
}