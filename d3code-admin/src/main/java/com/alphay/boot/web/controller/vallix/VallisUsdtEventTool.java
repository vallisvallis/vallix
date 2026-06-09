package com.alphay.boot.web.controller.vallix;

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
        try {
            Object[][] dataArray = restTemplate.getForObject(url, Object[][].class);

            if (dataArray == null || dataArray.length == 0) {
                return new ArrayList<>();
            }

            List<Vallisusdt> result = new ArrayList<>();
            for (Object[] arr : dataArray) {
                result.add(convertToVallisusdt(arr));
            }

            log.info("成功获取BTC K线数量：{}", result.size());
            return result;

        } catch (Exception e) {
            log.error("拉取币安BTC K线数据异常", e);
            return new ArrayList<>();
        }
    }

    /**
     * 币安数组 → 你的实体 Vallisusdt（完全不变）
     */
    private Vallisusdt convertToVallisusdt(Object[] arr) {
        Vallisusdt vo = new Vallisusdt();
        try {
            vo.setStartTime(new Date(Long.parseLong(arr[0].toString())));
            vo.setEndTime(new Date(Long.parseLong(arr[6].toString())));

            vo.setStartPrice(arr[1].toString());
            vo.setMaxPrice(arr[2].toString());
            vo.setMinPrice(arr[3].toString());
            vo.setEndPrice(arr[4].toString());

            vo.setCalcCount(arr[5].toString());
            vo.setPriceCount(arr[7].toString());
            vo.setNumCount(arr[8].toString());

            vo.setZdBuyCount(arr[9].toString());
            vo.setZdSellCount(arr[10].toString());

            vo.setCreateTime(new Date());
            vo.setUpdateTime(new Date());
        } catch (Exception e) {
            log.error("解析K线异常", e);
        }
        return vo;
    }
}