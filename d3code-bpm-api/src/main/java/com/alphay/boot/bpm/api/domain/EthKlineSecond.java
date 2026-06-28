// D:\vallix\d3code\d3code-bpm-api\src\main\java\com\alphay\boot\bpm\api\domain\EthKlineSecond.java
package com.alphay.boot.bpm.api.domain;

import com.alphay.boot.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ETH/USDT 秒级K线数据实体类
 *
 * 用于存储每秒的价格数据，保留最近一个月的数据
 *
 * @author d3code
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("eth_kline_second")
public class EthKlineSecond extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（数据库自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 时间戳（毫秒）
     */
    private Long timestamp;

    /**
     * 开盘价
     */
    @TableField(value = "open", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.IGNORED)
    private String open;

    /**
     * 收盘价
     */
    @TableField(value = "close", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.IGNORED)
    private String close;

    /**
     * 最高价
     */
    @TableField(value = "high", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.IGNORED)
    private String high;

    /**
     * 最低价
     */
    @TableField(value = "low", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.IGNORED)
    private String low;

    /**
     * 成交量
     */
    @TableField(value = "volume", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.IGNORED)
    private String volume;
}