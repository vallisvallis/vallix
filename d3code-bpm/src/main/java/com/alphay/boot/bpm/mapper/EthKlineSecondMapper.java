package com.alphay.boot.bpm.mapper;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.api.domain.EthKlineSecondCompareVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * ETH秒级K线数据Mapper接口
 *
 * @author d3code
 */
public interface EthKlineSecondMapper extends BaseMapper<EthKlineSecond> {

    /**
     * 根据时间范围查询K线数据
     *
     * @param startTime 开始时间（毫秒）
     * @param endTime   结束时间（毫秒）
     * @return K线数据集合
     */
    List<EthKlineSecond> selectByTimeRange(Long startTime, Long endTime);

    /**
     * 查询最近N条数据
     *
     * @param limit 数量限制
     * @return K线数据集合
     */
    List<EthKlineSecond> selectRecent(int limit);

    /**
     * 查询最近N条数据（包含10分钟对比）
     *
     * @param limit 数量限制
     * @return K线数据集合
     */
    List<EthKlineSecondCompareVO> selectRecentWithCompare(int limit);

    /**
     * 根据时间范围查询K线数据（包含10分钟对比）
     *
     * @param startTime 开始时间（毫秒）
     * @param endTime   结束时间（毫秒）
     * @return K线数据集合
     */
    List<EthKlineSecondCompareVO> selectByTimeRangeWithCompare(Long startTime, Long endTime);

    /**
     * 删除指定时间之前的数据（用于清理过期数据）
     *
     * @param beforeTime 时间戳（毫秒）
     * @return 删除数量
     */
    int deleteBefore(Long beforeTime);
}