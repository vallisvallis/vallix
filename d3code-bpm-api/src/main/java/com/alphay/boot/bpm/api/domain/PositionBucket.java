package com.alphay.boot.bpm.api.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 事件合约位置档位统计桶
 * <p>
 * 用于统计某个 RangePosition 区间内，未来固定窗口的涨跌胜率。
 * 币安事件合约赔率固定 1:0.8，数学保本线 = 55.56%。
 *
 * @author d3code
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionBucket {

    /** 档位标签，如 "极低位 0-20%" */
    private String label;

    /** 区间下限（含），0-100 */
    private double rangeMin;

    /** 区间上限（不含），0-100 */
    private double rangeMax;

    /** 本区间总有效样本数（up + down + flat） */
    private long totalSamples;

    /** 未来结算为 UP 的样本数 */
    private long upCount;

    /** 未来结算为 DOWN 的样本数 */
    private long downCount;

    /** 未来结算为 FLAT 的样本数 */
    private long flatCount;

    /** 上涨胜率 = upCount / totalSamples */
    private double upWinRate;

    /** 下跌胜率 = downCount / totalSamples */
    private double downWinRate;

    /** 做多是否期望为正（胜率 > 55.56%） */
    private boolean upProfitable;

    /** 做空是否期望为正（胜率 > 55.56%） */
    private boolean downProfitable;
}