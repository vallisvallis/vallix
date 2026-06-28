package com.alphay.boot.bpm.api.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ETH/USDT 秒级K线数据对比VO类
 *
 * 包含当前数据和10分钟前数据的对比信息
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EthKlineSecondCompareVO {

    /**
     * 当前数据时间戳
     */
    private Long timestamp;

    /**
     * 开盘价
     */
    private String open;

    /**
     * 收盘价
     */
    private String close;

    /**
     * 最高价
     */
    private String high;

    /**
     * 最低价
     */
    private String low;

    /**
     * 成交量
     */
    private String volume;

    /**
     * 10分钟前收盘价
     */
    private String close10MinAgo;

    /**
     * 价格变化
     */
    private String priceChange;

    /**
     * 价格变化百分比
     */
    private String priceChangePercent;
}