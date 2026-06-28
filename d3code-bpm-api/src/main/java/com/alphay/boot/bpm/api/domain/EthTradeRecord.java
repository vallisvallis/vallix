package com.alphay.boot.bpm.api.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.alphay.boot.common.core.domain.BaseEntity;

/**
 * ETH/USDT 实时交易记录表
 * 用于记录 WebSocket 实时策略触发的开单操作及结算结果
 *
 * @author d3code
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("eth_trade_record")
public class EthTradeRecord extends BaseEntity {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 开单时间戳（微秒） */
    private Long openTimestamp;

    /** 开单方向：多单/空单 */
    private String direction;

    /** 开单价格 */
    private String openPrice;

    /** 20分钟内最高价 */
    @TableField("high_20min")
    private String high20min;

    /** 20分钟内最高价出现的时间 */
    @TableField("high_20min_time")
    private String high20minTime;

    /** 20分钟内最低价 */
    @TableField("low_20min")
    private String low20min;

    /** 20分钟内最低价出现的时间 */
    @TableField("low_20min_time")
    private String low20minTime;

    /** 平仓时间戳（微秒） */
    private Long closeTimestamp;

    /** 平仓价格 */
    private String closePrice;

    /** 收益金额 */
    private String profit;

    /** 收益率（%） */
    private String profitPercent;

    /** 状态：待结算/盈利/亏损/超时 */
    private String status;
}