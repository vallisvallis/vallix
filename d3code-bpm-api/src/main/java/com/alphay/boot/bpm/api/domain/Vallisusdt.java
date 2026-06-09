package com.alphay.boot.bpm.api.domain;

import com.alphay.boot.common.annotation.Excel;
import com.alphay.boot.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * K线数据对象（严格按照币安API格式）
 * 
 * 币安API K线数据结构（GET /api/v3/klines）:
 * [0] openTime      - K线开始时间（毫秒时间戳）
 * [1] open          - 开盘价
 * [2] high          - 最高价
 * [3] low           - 最低价
 * [4] close         - 收盘价
 * [5] volume        - 成交量（基础资产）
 * [6] closeTime     - K线结束时间（毫秒时间戳）
 * [7] quoteVolume   - 成交额（报价资产）
 * [8] trades        - 成交笔数
 * [9] takerBuyBase  - 主动买入成交量
 * [10] takerBuyQuote - 主动买入成交额
 * [11] ignore       - 忽略字段
 *
 * @author d3code
 * @date 2025-05-26
 * @see <a href="https://binance-docs.github.io/apidocs/spot/en/#kline-candlestick-data">Binance API</a>
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("vallisusdt")
public class Vallisusdt extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（数据库自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * K线开始时间（毫秒时间戳）- 对应币安 [0] openTime
     */
    @Excel(name = "K线开始时间")
    private Long openTime;

    /**
     * 开盘价 - 对应币安 [1] open
     */
    @Excel(name = "开盘价")
    private String open;

    /**
     * 最高价 - 对应币安 [2] high
     */
    @Excel(name = "最高价")
    private String high;

    /**
     * 最低价 - 对应币安 [3] low
     */
    @Excel(name = "最低价")
    private String low;

    /**
     * 收盘价 - 对应币安 [4] close
     */
    @Excel(name = "收盘价")
    private String close;

    /**
     * 成交量（基础资产）- 对应币安 [5] volume
     */
    @Excel(name = "成交量")
    private String volume;

    /**
     * K线结束时间（毫秒时间戳）- 对应币安 [6] closeTime
     */
    @Excel(name = "K线结束时间")
    private Long closeTime;

    /**
     * 成交额（报价资产）- 对应币安 [7] quoteAssetVolume
     */
    @Excel(name = "成交额")
    private String quoteVolume;

    /**
     * 成交笔数 - 对应币安 [8] numberOfTrades
     */
    @Excel(name = "成交笔数")
    private Long trades;

    /**
     * 主动买入成交量 - 对应币安 [9] takerBuyBaseAssetVolume
     */
    @Excel(name = "主动买入成交量")
    private String takerBuyBase;

    /**
     * 主动买入成交额 - 对应币安 [10] takerBuyQuoteAssetVolume
     */
    @Excel(name = "主动买入成交额")
    private String takerBuyQuote;

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("openTime", getOpenTime())
                .append("open", getOpen())
                .append("high", getHigh())
                .append("low", getLow())
                .append("close", getClose())
                .append("volume", getVolume())
                .append("closeTime", getCloseTime())
                .append("quoteVolume", getQuoteVolume())
                .append("trades", getTrades())
                .append("takerBuyBase", getTakerBuyBase())
                .append("takerBuyQuote", getTakerBuyQuote())
                .toString();
    }
}