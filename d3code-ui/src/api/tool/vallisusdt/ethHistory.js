// D:\vallix\d3code\d3code-ui\src\api\tool\vallisusdt\ethHistory.js
import request from '@/utils/request'

/**
 * 查询最近N条秒级K线数据
 * @param {number} limit - 查询条数
 * @returns {Promise}
 */
export function getRecentKline(limit = 1000) {
    return request({
        url: '/system/ethkline/recent',
        method: 'get',
        params: { limit }
    })
}

/**
 * 根据时间范围查询秒级K线数据
 * @param {number} startTime - 开始时间戳（毫秒）
 * @param {number} endTime - 结束时间戳（毫秒）
 * @returns {Promise}
 */
export function getKlineByTimeRange(startTime, endTime) {
    return request({
        url: '/system/ethkline/range',
        method: 'get',
        params: { startTime, endTime }
    })
}

/**
 * 查询最近N条秒级K线数据（包含10分钟对比）
 * @param {number} limit - 查询条数
 * @returns {Promise}
 */
export function getRecentKlineWithCompare(limit = 1000) {
    return request({
        url: '/system/ethkline/recentWithCompare',
        method: 'get',
        params: { limit }
    })
}

/**
 * 根据时间范围查询秒级K线数据（包含10分钟对比）
 * @param {number} startTime - 开始时间戳（毫秒）
 * @param {number} endTime - 结束时间戳（毫秒）
 * @returns {Promise}
 */
export function getKlineByTimeRangeWithCompare(startTime, endTime) {
    return request({
        url: '/system/ethkline/rangeWithCompare',
        method: 'get',
        params: { startTime, endTime }
    })
}