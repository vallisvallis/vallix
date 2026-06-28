package com.alphay.boot.bpm.mapper;

import com.alphay.boot.bpm.api.domain.KlineFuturePrice;
import com.alphay.boot.bpm.api.domain.PriceChangeStat;
import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * BTC K线数据Mapper接口
 *
 * @author d3code
 * @date 2025-05-26
 */
public interface VallisusdtMapper extends BaseMapper<Vallisusdt> {
    /**
     * 查询K线数据列表
     *
     * @param vallisusdt K线数据
     * @return K线数据集合
     */
    public List<Vallisusdt> selectVallisusdtList(Vallisusdt vallisusdt);

    /**
     * 使用LEAD窗口函数统计每条数据10分钟后的价格变化
     * 10分钟 = 10条分钟K线，所以使用LEAD(close, 10)
     *
     * @return 价格变化统计结果
     */
    public List<PriceChangeStat> selectPriceChangeStats();

    /**
     * 批量获取所有K线的 OHLC 价格（已按时间升序、过滤 deleted=0）。
     * <p>
     * futureClose 不在 SQL 中计算，由 Java 层用数组下标索引 O(1) 直接跳转。
     *
     * @return K线价格列表（不含未来结算价）
     */
    public List<KlineFuturePrice> selectKlineWithFuturePrice();

}