import request from '@/utils/request'
/**
 * 策略A回测（10分钟均值回归）
 * @param {number} dataPoints - 数据点数，默认2000
 */
export function backtestStrategyA(dataPoints = 2000) {
  return request({
    url: '/system/vallisusdt/backtest/strategyA',
    method: 'get',
    params: { dataPoints }
  })
}

/**
 * 策略B回测（30分钟趋势突破）
 * @param {number} dataPoints - 数据点数，默认2000
 */
export function backtestStrategyB(dataPoints = 2000) {
  return request({
    url: '/system/vallisusdt/backtest/strategyB',
    method: 'get',
    params: { dataPoints }
  })
}

/**
 * 综合回测
 * @param {number} dataPoints - 数据点数，默认2000
 */
export function comprehensiveBackTest(dataPoints = 2000) {
  return request({
    url: '/system/vallisusdt/backtest/comprehensive',
    method: 'get',
    params: { dataPoints }
  })
}

/**
 * 兼容原有接口
 */
export function backtest() {
  return request({
    url: '/system/vallisusdt/backtest',
    method: 'get'
  })
}

// ===================== 实时监控接口 =====================

/**
 * 获取实时数据
 */
export function getRealData() {
  return request({
    url: '/system/vallisusdt/real',
    method: 'get'
  })
}

/**
 * 获取信号历史
 * @param {number} limit - 限制条数，默认20
 */
export function getSignalHistory(limit = 20) {
  return request({
    url: '/system/vallisusdt/signals/history',
    method: 'get',
    params: { limit }
  })
}

/**
 * 获取交易历史
 * @param {number} limit - 限制条数，默认20
 */
export function getTradeHistory(limit = 20) {
  return request({
    url: '/system/vallisusdt/trades/history',
    method: 'get',
    params: { limit }
  })
}

// ===================== 统计分析接口 =====================

/**
 * 获取统计信息
 * @param {number} dataPoints - 数据点数，默认1000
 */
export function getStatistics(dataPoints = 1000) {
  return request({
    url: '/system/vallisusdt/statistics',
    method: 'get',
    params: { dataPoints }
  })
}

/**
 * 获取市场状态
 * @param {number} dataPoints - 数据点数，默认500
 */
export function getMarketStatus(dataPoints = 500) {
  return request({
    url: '/system/vallisusdt/market/status',
    method: 'get',
    params: { dataPoints }
  })
}

// ===================== 交易管理接口 =====================

/**
 * 手动开仓
 * @param {string} direction - 方向：'做多' 或 '做空'
 * @param {number} positionSize - 仓位大小，默认0.01（1%）
 */
export function openPosition(direction, positionSize = 0.01) {
  return request({
    url: '/system/vallisusdt/trade/open',
    method: 'post',
    params: { direction, positionSize }
  })
}

/**
 * 手动平仓
 */
export function closePosition() {
  return request({
    url: '/system/vallisusdt/trade/close',
    method: 'post'
  })
}

/**
 * 获取持仓状态
 */
export function getPositionStatus() {
  return request({
    url: '/system/vallisusdt/trade/position',
    method: 'get'
  })
}

// ===================== 策略参数接口 =====================

/**
 * 获取策略参数
 */
export function getStrategyParameters() {
  return request({
    url: '/system/vallisusdt/strategy/parameters',
    method: 'get'
  })
}

/**
 * 更新策略参数
 * @param {Object} parameters - 新的参数对象
 */
export function updateStrategyParameters(parameters) {
  return request({
    url: '/system/vallisusdt/strategy/parameters/update',
    method: 'post',
    data: parameters
  })
}

// ===================== 系统健康检查 =====================

/**
 * 系统健康检查
 */
export function healthCheck() {
  return request({
    url: '/system/vallisusdt/health',
    method: 'get'
  })
}

// ===================== 数据管理接口 =====================

/**
 * 获取原始K线数据
 * @param {number} limit - 数据条数，默认100
 * @param {string} interval - 时间间隔，默认'1m'
 */
export function getRawKlineData(limit = 100, interval = '1m') {
  return request({
    url: '/system/vallisusdt/data/raw',
    method: 'get',
    params: { limit, interval }
  })
}

/**
 * 获取聚合K线数据
 * @param {number} oneMinLimit - 1分钟数据条数，默认1000
 * @param {string} timeframe - 时间框架：'10min', '30min', 'all'
 */
export function getAggregatedKlineData(oneMinLimit = 1000, timeframe = '10min') {
  return request({
    url: '/system/vallisusdt/data/aggregated',
    method: 'get',
    params: { oneMinLimit, timeframe }
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

/**
 * 获取信号颜色类
 * @param {string} signal - 信号：'做多', '做空', '止损', '观望'
 */
export function getSignalClass(signal) {
  switch (signal) {
    case '做多':
      return 'signal-long'
    case '做空':
      return 'signal-short'
    case '止损':
      return 'signal-stop'
    case '强烈做多':
    case '强烈做空':
      return 'signal-strong'
    default:
      return 'signal-wait'
  }
}

/**
 * 获取风险等级颜色类
 * @param {string} riskLevel - 风险等级：'高', '中', '低'
 */
export function getRiskClass(riskLevel) {
  switch (riskLevel) {
    case '高':
      return 'risk-high'
    case '中':
      return 'risk-medium'
    case '低':
      return 'risk-low'
    default:
      return 'risk-unknown'
  }
}

/**
 * 计算建议仓位
 * @param {string} riskLevel - 风险等级
 * @param {string} signalStrength - 信号强度：'强烈', '普通'
 */
export function calculateRecommendedPosition(riskLevel, signalStrength = '普通') {
  const basePositions = {
    '高': 0.005,  // 0.5%
    '中': 0.01,   // 1%
    '低': 0.02    // 2%
  }

  let position = basePositions[riskLevel] || 0.01

  if (signalStrength === '强烈') {
    position *= 1.5
  }

  return Math.min(position, 0.03) // 最大3%
}

// ===================== 常量定义 =====================

export const STRATEGY_PARAMS = {
  // 10分钟策略
  MIN10: {
    ENTRY_THRESHOLD: 0.056,   // 入场阈值 0.056%
    STOP_LOSS_THRESHOLD: 0.198, // 止损阈值 0.198%
    TARGET_RETURN: 0.005,     // 目标收益 0.005%
    HOLD_PERIOD: '10-20分钟'
  },

  // 30分钟策略
  MIN30: {
    STOP_LOSS: 0.3,           // 止损 0.3%
    TAKE_PROFIT: 0.6,         // 止盈 0.6%
    HOLD_PERIOD: '30-90分钟'
  },

  // 风险管理
  RISK_MANAGEMENT: {
    MAX_POSITION_SIZE: 0.02,  // 最大仓位 2%
    DAILY_MAX_LOSS: 0.05,     // 日最大亏损 5%
    WEEKLY_MAX_LOSS: 0.15,    // 周最大亏损 15%
    MAX_CONSECUTIVE_LOSSES: 3 // 最大连续亏损次数
  },

  // 交易时段
  TRADING_SESSIONS: {
    BEST: 'UTC 0:00-8:00（亚洲时段）',
    AVOID: 'UTC 8:00-10:00, 20:00-22:00',
    MAX_DAILY_TRADES: 10
  }
}

export const SIGNAL_TYPES = {
  LONG: '做多',
  SHORT: '做空',
  STOP_LOSS: '止损',
  WAIT: '观望',
  STRONG_LONG: '强烈做多',
  STRONG_SHORT: '强烈做空',
  CONFLICT: '信号冲突-观望',
  NO_TRADE: '不交易'
}

export const MARKET_STATUS = {
  RANGING_LOW: '震荡市（低波动）',
  RANGING_NORMAL: '震荡市（正常波动）',
  TRENDING_HIGH: '趋势市（高波动）',
  TRENDING_NORMAL: '趋势市（正常波动）',
  BALANCED: '平衡市（正常波动）',
  UNKNOWN: '未知'
}

export const RISK_LEVELS = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低'
}

// ===================== 默认导出 =====================

export default {
  // 回测接口
  backtestStrategyA,
  backtestStrategyB,
  comprehensiveBackTest,
  backtest,

  // 实时监控
  getRealData,
  getSignalHistory,
  getTradeHistory,

  // 统计分析
  getStatistics,
  getMarketStatus,

  // 交易管理
  openPosition,
  closePosition,
  getPositionStatus,

  // 策略参数
  getStrategyParameters,
  updateStrategyParameters,

  // 系统健康
  healthCheck,

  // 数据管理
  getRawKlineData,
  getAggregatedKlineData,

  // 工具函数
  formatPercent,
  formatPrice,
  getSignalClass,
  getRiskClass,
  calculateRecommendedPosition,

  // 常量
  STRATEGY_PARAMS,
  SIGNAL_TYPES,
  MARKET_STATUS,
  RISK_LEVELS
}
