package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.api.domain.EthKlineSecondCompareVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * ETH秒级K线数据Service接口
 *
 * @author d3code
 */
public interface IEthKlineSecondService extends IService<EthKlineSecond> {

    /**
     * 保存秒级K线数据
     *
     * @param data K线数据
     * @return 是否成功
     */
    boolean saveKlineData(EthKlineSecond data);

    /**
     * 批量保存秒级K线数据
     *
     * @param dataList K线数据列表
     * @return 是否成功
     */
    boolean saveBatchKlineData(List<EthKlineSecond> dataList);

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
     * 清理过期数据（保留最近一个月）
     *
     * @return 删除数量
     */
    int cleanExpiredData();
}