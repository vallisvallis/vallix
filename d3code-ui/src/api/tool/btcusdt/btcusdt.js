import request from '@/utils/request'

/**
 * 获取BTC原始K线数据
 * @param {number} limit - 数据条数，默认100
 * @param {string} interval - 时间间隔，默认'1m'
 */
export function getBtcRawKlineData(limit = 100, interval = '1m') {
    return request({
        url: '/system/vallisusdt/btc/data/raw',
        method: 'get',
        params: { limit, interval }
    })
}

/**
 * 获取BTC聚合K线数据
 * @param {number} oneMinLimit - 1分钟数据条数，默认1000
 * @param {string} timeframe - 时间框架：'10min', '30min', '60min', 'all'
 */
export function getBtcAggregatedKlineData(oneMinLimit = 1000, timeframe = '10min') {
    return request({
        url: '/system/vallisusdt/btc/data/aggregated',
        method: 'get',
        params: { oneMinLimit, timeframe }
    })
}

/**
 * 获取BTC价格预测统计分析
 * @param {number} dataPoints - 数据点数，默认1000
 */
export function getBtcPredictionAnalysis(dataPoints = 1000) {
  return request({
    url: '/system/vallisusdt/btc/statistics/prediction-analysis',
    method: 'get',
    params: { dataPoints }
  })
}

/**
 * BTC特征工程回测分析
 * @param {number} dataPoints - 数据点数，默认1000
 * @param {number} predictWindow - 预测窗口（分钟），默认5
 */
export function backtestWithFeatures(dataPoints = 1000, predictWindow = 5) {
  return request({
    url: '/system/vallisusdt/btc/feature/backtest',
    method: 'get',
    params: { dataPoints, predictWindow },
    timeout: 120000 // 增加到2分钟
  })
}

/**
 * BTC实时预测
 */
export function realtimePredict() {
  return request({
    url: '/system/vallisusdt/btc/feature/realtime-predict',
    method: 'get',
    timeout: 30000 // 实时预测30秒足够
  })
}

// ===================== 工具函数 =====================

/**
 * 格式化百分比
 * @param {number} value - 原始值
 * @param {number} decimals - 小数位数，默认4
 */
export function formatPercent(value, decimals = 4) {
    if (value === null || value === undefined) return '-'
    return `${value.toFixed(decimals)}%`
}

/**
 * 格式化价格
 * @param {string|number} price - 价格
 * @param {number} decimals - 小数位数，默认2
 */
export function formatPrice(price, decimals = 2) {
    if (!price) return '-'
    const num = typeof price === 'string' ? parseFloat(price) : price
    return num.toFixed(decimals)
}

export default {
    getBtcRawKlineData,
    getBtcAggregatedKlineData,
    formatPercent,
    formatPrice
}
