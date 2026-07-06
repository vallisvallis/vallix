package com.alphay.boot.bpm.mapper;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.api.domain.EthKlineSecondCompareVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

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
    List<EthKlineSecond> selectByTimeRange(@Param("startTime") Long startTime, @Param("endTime") Long endTime);

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
    List<EthKlineSecondCompareVO> selectByTimeRangeWithCompare(@Param("startTime") Long startTime, @Param("endTime") Long endTime);

    /**
     * 删除指定时间之前的数据（用于清理过期数据）
     *
     * @param beforeTime 时间戳（毫秒）
     * @return 删除数量
     */
    int deleteBefore(@Param("beforeTime") Long beforeTime);

    /**
     * 查找数据断档（相邻两条记录时间差 > 1秒）
     * 使用 LEAD() 窗口函数，一条SQL返回所有断档位置
     *
     * @return 断档信息列表，每项包含 [prev_ts, curr_ts, gap_seconds]
     */
    List<Map<String, Object>> findGaps();
}