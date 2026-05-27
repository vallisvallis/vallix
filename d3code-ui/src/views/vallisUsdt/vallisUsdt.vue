<template>
  <div class="monitor-container">
    <!-- 头部标题和状态 -->
    <div class="header">
      <div class="title-section">
        <h1>ETH/USDT 事件合约智能交易系统</h1>
        <div class="status-bar">
          <span class="update-time">最后更新: {{ formatTime(updateTime) }}</span>
          <span class="refresh-btn" @click="forceRefresh">
            <i class="el-icon-refresh" :class="{ 'refreshing': isRefreshing }"></i>
            刷新
          </span>
          <el-tag :type="healthStatus.type" size="small">
            {{ healthStatus.text }}
          </el-tag>
        </div>
      </div>
    </div>

    <!-- 主要监控面板 -->
    <div class="main-content">
      <!-- 左侧：实时数据 -->
      <div class="left-panel">
        <!-- 市场概览 -->
        <div class="card market-overview">
          <div class="card-header">
            <h3><i class="el-icon-data-line"></i> 市场概览</h3>
            <el-tag :type="getRiskType(riskLevel)" size="small">
              风险等级: {{ riskLevel }}
            </el-tag>
          </div>
          <div class="card-body">
            <div class="overview-grid">
              <div class="overview-item">
                <label>市场状态</label>
                <span :class="getMarketStatusClass(marketStatus)">{{ marketStatus }}</span>
              </div>
              <div class="overview-item">
                <label>交易时段</label>
                <span>{{ tradingSession }}</span>
              </div>
              <div class="overview-item">
                <label>连续同向K线</label>
                <span :class="continuous > 3 ? 'warning' : 'normal'">{{ continuous }}</span>
              </div>
              <div class="overview-item">
                <label>10分钟波动率</label>
                <span>{{ formatPercent(volatility10min) }}</span>
              </div>
              <div class="overview-item">
                <label>30分钟波动率</label>
                <span>{{ formatPercent(volatility30min) }}</span>
              </div>
              <div class="overview-item">
                <label>连续亏损</label>
                <span :class="losingStreak > 2 ? 'danger' : 'normal'">{{ losingStreak }} 次</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 10分钟周期 -->
        <div class="card period-10min">
          <div class="card-header">
            <h3><i class="el-icon-timer"></i> 10分钟周期（均值回归策略）</h3>
            <el-tag :type="getSignalType(signal10)" size="small">
              {{ signal10 }}
            </el-tag>
          </div>
          <div class="card-body">
            <div class="price-grid">
              <div class="price-item">
                <label>开盘价</label>
                <span class="price">{{ formatPrice(min10.start_price) }}</span>
              </div>
              <div class="price-item">
                <label>收盘价</label>
                <span class="price">{{ formatPrice(min10.end_price) }}</span>
              </div>
              <div class="price-item">
                <label>最高价</label>
                <span class="price high">{{ formatPrice(min10.max_price) }}</span>
              </div>
              <div class="price-item">
                <label>最低价</label>
                <span class="price low">{{ formatPrice(min10.min_price) }}</span>
              </div>
              <div class="price-item">
                <label>涨跌幅</label>
                <span :class="min10.change_value > 0 ? 'up' : 'down'">
                  {{ formatPercent(min10.change_value) }}
                </span>
              </div>
              <div class="price-item">
                <label>成交量</label>
                <span>{{ formatVolume(min10.volume) }} ETH</span>
              </div>
            </div>
            <div class="strategy-info">
              <div class="strategy-param">
                <label>入场阈值</label>
                <span>{{ STRATEGY_PARAMS.MIN10.ENTRY_THRESHOLD }}%</span>
              </div>
              <div class="strategy-param">
                <label>止损阈值</label>
                <span>{{ STRATEGY_PARAMS.MIN10.STOP_LOSS_THRESHOLD }}%</span>
              </div>
              <div class="strategy-param">
                <label>目标收益</label>
                <span>{{ STRATEGY_PARAMS.MIN10.TARGET_RETURN }}%</span>
              </div>
              <div class="strategy-param">
                <label>持仓时间</label>
                <span>{{ STRATEGY_PARAMS.MIN10.HOLD_PERIOD }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 30分钟周期 -->
        <div class="card period-30min">
          <div class="card-header">
            <h3><i class="el-icon-time"></i> 30分钟周期（趋势突破策略）</h3>
            <el-tag :type="getSignalType(signal30)" size="small">
              {{ signal30 }}
            </el-tag>
          </div>
          <div class="card-body">
            <div class="price-grid">
              <div class="price-item">
                <label>开盘价</label>
                <span class="price">{{ formatPrice(min30.start_price) }}</span>
              </div>
              <div class="price-item">
                <label>收盘价</label>
                <span class="price">{{ formatPrice(min30.end_price) }}</span>
              </div>
              <div class="price-item">
                <label>最高价</label>
                <span class="price high">{{ formatPrice(min30.max_price) }}</span>
              </div>
              <div class="price-item">
                <label>最低价</label>
                <span class="price low">{{ formatPrice(min30.min_price) }}</span>
              </div>
              <div class="price-item">
                <label>涨跌幅</label>
                <span :class="min30.changeValue > 0 ? 'up' : 'down'">
                  {{ formatPercent(min30.change_value) }}
                </span>
              </div>
              <div class="price-item">
                <label>成交量</label>
                <span>{{ formatVolume(min30.volume) }} ETH</span>
              </div>
            </div>
            <div class="strategy-info">
              <div class="strategy-param">
                <label>止损</label>
                <span>{{ STRATEGY_PARAMS.MIN30.STOP_LOSS }}%</span>
              </div>
              <div class="strategy-param">
                <label>止盈</label>
                <span>{{ STRATEGY_PARAMS.MIN30.TAKE_PROFIT }}%</span>
              </div>
              <div class="strategy-param">
                <label>持仓时间</label>
                <span>{{ STRATEGY_PARAMS.MIN30.HOLD_PERIOD }}</span>
              </div>
              <div class="strategy-param">
                <label>风险回报比</label>
                <span>1:2</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧：交易信号和操作 -->
      <div class="right-panel">
        <!-- 综合信号 -->
        <div class="card overall-signal">
          <div class="card-header">
            <h3><i class="el-icon-s-operation"></i> 综合交易信号</h3>
            <el-tag :type="getSignalType(overallSignal)" size="small" effect="dark">
              {{ overallSignal }}
            </el-tag>
          </div>
          <div class="card-body">
            <div class="signal-analysis">
              <div class="analysis-item">
                <label>信号强度</label>
                <span :class="getSignalStrengthClass(signalStrength)">
                  {{ signalStrength }}
                </span>
              </div>
              <div class="analysis-item">
                <label>成交量确认</label>
                <span :class="volumeConfirm ? 'confirm' : 'no-confirm'">
                  {{ volumeConfirm ? '✓ 已确认' : '✗ 未确认' }}
                </span>
              </div>
              <div class="analysis-item">
                <label>建议操作</label>
                <span class="recommended-action">{{ recommendedAction }}</span>
              </div>
              <div class="analysis-item">
                <label>建议仓位</label>
                <span class="recommended-position">{{ recommendedPosition }}%</span>
              </div>
            </div>

            <!-- 操作按钮 -->
            <div class="action-buttons" v-if="showTradingButtons">
              <el-button
                type="success"
                :disabled="!canOpenLong"
                @click="openPosition('做多')"
                :loading="openingPosition === '做多'">
                <i class="el-icon-top"></i> 开仓做多
              </el-button>
              <el-button
                type="danger"
                :disabled="!canOpenShort"
                @click="openPosition('做空')"
                :loading="openingPosition === '做空'">
                <i class="el-icon-bottom"></i> 开仓做空
              </el-button>
              <el-button
                type="warning"
                :disabled="!inPosition"
                @click="closePosition"
                :loading="closingPosition">
                <i class="el-icon-switch-button"></i> 平仓
              </el-button>
            </div>

            <!-- 持仓信息 -->
            <div class="position-info" v-if="inPosition && currentPosition">
              <h4>当前持仓</h4>
              <div class="position-details">
                <div class="detail-item">
                  <label>方向</label>
                  <span :class="currentPosition.direction === '做多' ? 'long' : 'short'">
                    {{ currentPosition.direction }}
                  </span>
                </div>
                <div class="detail-item">
                  <label>开仓价</label>
                  <span>{{ formatPrice(currentPosition.entry_price) }}</span>
                </div>
                <div class="detail-item">
                  <label>当前价</label>
                  <span>{{ formatPrice(min10.endPrice) }}</span>
                </div>
                <div class="detail-item">
                  <label>浮动盈亏</label>
                  <span :class="currentPL >= 0 ? 'profit' : 'loss'">
                    {{ formatPercent(currentPL) }}
                  </span>
                </div>
                <div class="detail-item">
                  <label>止损价</label>
                  <span class="stop-loss">{{ formatPrice(currentPosition.stop_loss) }}</span>
                </div>
                <div class="detail-item">
                  <label>止盈价</label>
                  <span class="take-profit">{{ formatPrice(currentPosition.take_profit) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 回测面板 -->
        <div class="card backtest-panel">
          <div class="card-header">
            <h3><i class="el-icon-s-data"></i> 策略回测</h3>
            <el-button-group>
              <el-button
                size="small"
                @click="runBackTest('strategyA')"
                :loading="backtestLoading === 'strategyA'">
                策略A
              </el-button>
              <el-button
                size="small"
                @click="runBackTest('strategyB')"
                :loading="backtestLoading === 'strategyB'">
                策略B
              </el-button>
              <el-button
                size="small"
                @click="runBackTest('comprehensive')"
                :loading="backtestLoading === 'comprehensive'">
                综合
              </el-button>
            </el-button-group>
          </div>
          <div class="card-body">
            <div v-if="backtestResult" class="backtest-result">
              <div class="result-header">
                <h4>{{ backtestResult.strategy_name }}</h4>
                <el-tag :type="backtestResult.win_rate > 60 ? 'success' : 'warning'">
                  胜率: {{ backtestResult.win_rate }}
                </el-tag>
              </div>
              <div class="result-grid">
                <div class="result-item">
                  <label>总交易次数</label>
                  <span>{{ backtestResult.total_trades }}</span>
                </div>
                <div class="result-item">
                  <label>盈利次数</label>
                  <span>{{ backtestResult.winning_trades }}</span>
                </div>
                <div class="result-item">
                  <label>总收益</label>
                  <span :class="parseFloat(backtestResult.total_profit) > 0 ? 'profit' : 'loss'">
                    {{ backtestResult.total_profit }}
                  </span>
                </div>
                <div class="result-item">
                  <label>最大回撤</label>
                  <span class="drawdown">{{ backtestResult.max_drawdown }}</span>
                </div>
                <div class="result-item">
                  <label>夏普比率</label>
                  <span :class="parseFloat(backtestResult.sharpe_ratio) > 1 ? 'good' : 'bad'">
                    {{ backtestResult.sharpe_ratio }}
                  </span>
                </div>
                <div class="result-item">
                  <label>盈亏比</label>
                  <span>{{ backtestResult.profit_loss_ratio }}</span>
                </div>
              </div>
            </div>
            <div v-else class="no-result">
              <p>点击上方按钮运行回测</p>
            </div>
          </div>
        </div>

        <!-- 信号历史 -->
        <div class="card signal-history">
          <div class="card-header">
            <h3><i class="el-icon-notebook-2"></i> 信号历史</h3>
            <el-button size="small" @click="loadSignalHistory">刷新</el-button>
          </div>
          <div class="card-body">
            <div class="history-list">
              <div v-for="(signal, index) in signalHistory" :key="index" class="history-item">
                <div class="time">{{ formatTime(signal.time) }}</div>
                <div class="signal">
                  <el-tag :type="getSignalType(signal.overall_signal)" size="mini">
                    {{ signal.overall_signal }}
                  </el-tag>
                </div>
                <div class="details">
                  <span>10分: {{ signal['10min_signal'] }}</span>
                  <span>30分: {{ signal['30min_signal'] }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部统计信息 -->
    <div class="footer">
      <div class="statistics">
        <div class="stat-item">
          <label>系统运行时间</label>
          <span>{{ formatDuration(runTime) }}</span>
        </div>
        <div class="stat-item">
          <label>总交易次数</label>
          <span>{{ totalTrades }}</span>
        </div>
        <div class="stat-item">
          <label>总胜率</label>
          <span :class="overallWinRate > 60 ? 'good' : 'bad'">
            {{ formatPercent(overallWinRate, 1) }}
          </span>
        </div>
        <div class="stat-item">
          <label>总收益</label>
          <span :class="totalProfit >= 0 ? 'profit' : 'loss'">
            {{ formatPercent(totalProfit, 4) }}
          </span>
        </div>
        <div class="stat-item">
          <label>最大回撤</label>
          <span class="drawdown">{{ formatPercent(maxDrawdown, 2) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import * as api from '@/api/tool/vallisusdt/vallisusdt'

export default {
  name: 'VallisusdtMonitor',
  data() {
    return {
      // 监控数据
      updateTime: null,
      isRefreshing: false,
      healthStatus: { type: 'success', text: '正常' },

      // 市场数据
      marketStatus: '未知',
      tradingSession: '未知',
      riskLevel: '中',
      continuous: 0,
      volatility10min: 0,
      volatility30min: 0,
      losingStreak: 0,

      // 10分钟数据
      min10: {
        startPrice: '0',
        endPrice: '0',
        maxPrice: '0',
        minPrice: '0',
        changeValue: 0,
        volume: '0'
      },
      signal10: '观望',

      // 30分钟数据
      min30: {
        startPrice: '0',
        endPrice: '0',
        maxPrice: '0',
        minPrice: '0',
        changeValue: 0,
        volume: '0'
      },
      signal30: '观望',

      // 综合信号
      overallSignal: '观望',
      signalStrength: '普通',
      volumeConfirm: false,
      recommendedAction: '保持观望',
      recommendedPosition: 1,

      // 交易状态
      inPosition: false,
      currentPosition: null,
      currentPL: 0,
      openingPosition: null,
      closingPosition: false,
      showTradingButtons: true, // 实际项目中应该根据用户权限设置

      // 回测
      backtestLoading: null,
      backtestResult: null,

      // 历史
      signalHistory: [],

      // 统计
      runTime: 0,
      totalTrades: 0,
      overallWinRate: 0,
      totalProfit: 0,
      maxDrawdown: 0,

      // 定时器
      timer: null,
      startTime: Date.now()
    }
  },

  computed: {
    // 策略参数常量
    STRATEGY_PARAMS() {
      return api.STRATEGY_PARAMS
    },

    // 是否可以开多仓
    canOpenLong() {
      return !this.inPosition &&
        this.overallSignal.includes('做多') &&
        this.riskLevel !== '高' &&
        this.volumeConfirm
    },

    // 是否可以开空仓
    canOpenShort() {
      return !this.inPosition &&
        this.overallSignal.includes('做空') &&
        this.riskLevel !== '高' &&
        this.volumeConfirm
    }
  },

  created() {
    this.initMonitor()
  },

  beforeDestroy() {
    this.clearTimers()
  },

  methods: {
    // 初始化监控
    async initMonitor() {
      // 健康检查
      await this.checkHealth()

      // 加载初始数据
      await this.loadRealData()
      await this.loadSignalHistory()
      await this.loadPositionStatus()

      // 启动定时器
      this.startTimers()
    },

    // 启动定时器
    startTimers() {
      // 每10秒更新实时数据
      this.timer = setInterval(() => {
        this.loadRealData()
      }, 10000)

      // 每60秒更新运行时间
      setInterval(() => {
        this.runTime = Date.now() - this.startTime
      }, 60000)
    },

    // 清理定时器
    clearTimers() {
      if (this.timer) {
        clearInterval(this.timer)
        this.timer = null
      }
    },

    // 健康检查
    async checkHealth() {
      try {
        const res = await api.healthCheck()
        if (res.code === 200) {
          this.healthStatus = { type: 'success', text: '正常' }
        } else {
          this.healthStatus = { type: 'danger', text: '异常' }
        }
      } catch (error) {
        this.healthStatus = { type: 'danger', text: '连接失败' }
      }
    },

    // 加载实时数据
    async loadRealData() {
      try {
        this.isRefreshing = true
        const res = await api.getRealData()

        if (res.code === 200) {
          const data = res.data
          this.updateTime = Date.now()
          console.log('实时数据:', data)
          // 更新市场数据
          this.marketStatus = data.market_status || '未知'
          this.tradingSession = data.trading_session || '未知'
          this.continuous = data.continuous_kline || 0

          // 更新10分钟数据
          if (data.min10) {
            this.min10 = data.min10
            this.signal10 = data.signals?.['10min_signal'] || '观望'
          }

          // 更新30分钟数据
          if (data.min30) {
            this.min30 = data.min30
            this.signal30 = data.signals?.['30min_signal'] || '观望'
          }

          // 更新信号数据
          if (data.signals) {
            this.overallSignal = data.signals.overall_signal || '观望'
            this.recommendedAction = data.signals.recommended_action || '保持观望'
            this.volumeConfirm = data.signals.volume_confirm_10min || false

            // 计算信号强度
            this.signalStrength = this.overallSignal.startsWith('强烈') ? '强烈' : '普通'

            // 计算建议仓位
            const riskAssessment = data.signals.risk_assessment || {}
            this.riskLevel = riskAssessment.risk_level || '中'
            this.volatility10min = parseFloat(riskAssessment.volatility_10min) || 0
            this.volatility30min = parseFloat(riskAssessment.volatility_30min) || 0
            this.losingStreak = riskAssessment.losing_streak || 0

            this.recommendedPosition = api.calculateRecommendedPosition(
              this.riskLevel,
              this.signalStrength
            ) * 100
          }

          // 更新持仓盈亏
          if (this.inPosition && this.currentPosition) {
            this.calculateCurrentPL()
          }
        }
      } catch (error) {
        console.error('加载实时数据失败:', error)
      } finally {
        this.isRefreshing = false
      }
    },

    // 加载信号历史
    async loadSignalHistory() {
      try {
        const res = await api.getSignalHistory(10)
        if (res.code === 200) {
          this.signalHistory = res.data || []
        }
      } catch (error) {
        console.error('加载信号历史失败:', error)
      }
    },

    // 加载持仓状态
    async loadPositionStatus() {
      try {
        const res = await api.getPositionStatus()
        if (res.code === 200) {
          this.inPosition = res.data.in_position || false
          this.currentPosition = res.data.current_position || null

          if (this.inPosition && this.currentPosition) {
            this.calculateCurrentPL()
          }
        }
      } catch (error) {
        console.error('加载持仓状态失败:', error)
      }
    },

    // 计算当前盈亏
    calculateCurrentPL() {
      if (!this.currentPosition || !this.min10.endPrice) return

      const entryPrice = parseFloat(this.currentPosition.entry_price)
      const currentPrice = parseFloat(this.min10.endPrice)

      if (this.currentPosition.direction === '做多') {
        this.currentPL = (currentPrice - entryPrice) / entryPrice * 100
      } else {
        this.currentPL = (entryPrice - currentPrice) / entryPrice * 100
      }
    },

    // 强制刷新
    forceRefresh() {
      this.loadRealData()
      this.checkHealth()
    },

    // 开仓
    async openPosition(direction) {
      try {
        this.openingPosition = direction

        // 确认对话框
        await this.$confirm(
          `确定要${direction}吗？\n建议仓位: ${this.recommendedPosition}%`,
          '确认开仓',
          {
            confirmButtonText: '确定',
            cancelButtonText: '取消',
            type: 'warning'
          }
        )

        const res = await api.openPosition(direction, this.recommendedPosition / 100)
        if (res.code === 200) {
          this.$message.success('开仓成功')
          await this.loadPositionStatus()
        } else {
          this.$message.error(res.msg || '开仓失败')
        }
      } catch (error) {
        if (error !== 'cancel') {
          this.$message.error('开仓异常: ' + error.message)
        }
      } finally {
        this.openingPosition = null
      }
    },

    // 平仓
    async closePosition() {
      try {
        this.closingPosition = true

        await this.$confirm('确定要平仓吗？', '确认平仓', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })

        const res = await api.closePosition()
        if (res.code === 200) {
          this.$message.success('平仓成功')
          await this.loadPositionStatus()
        } else {
          this.$message.error(res.msg || '平仓失败')
        }
      } catch (error) {
        if (error !== 'cancel') {
          this.$message.error('平仓异常: ' + error.message)
        }
      } finally {
        this.closingPosition = false
      }
    },

    // 运行回测
    async runBackTest(strategy) {
      try {
        this.backtestLoading = strategy

        let res
        switch (strategy) {
          case 'strategyA':
            res = await api.backtestStrategyA(2000)
            break
          case 'strategyB':
            res = await api.backtestStrategyB(2000)
            break
          case 'comprehensive':
            res = await api.comprehensiveBackTest(2000)
            break
        }

        if (res.code === 200) {
          if (strategy === 'comprehensive') {
            // 综合回测显示策略A的结果
            this.backtestResult = res.data.strategyA
          } else {
            this.backtestResult = res.data
          }
          this.$message.success('回测完成')
        } else {
          this.$message.error(res.msg || '回测失败')
        }
      } catch (error) {
        this.$message.error('回测异常: ' + error.message)
      } finally {
        this.backtestLoading = null
      }
    },

    // 格式化工具函数
    formatTime(timestamp) {
      if (!timestamp) return '-'
      const date = new Date(timestamp)
      return date.toLocaleString('zh-CN')
    },

    formatDuration(ms) {
      if (!ms) return '0秒'
      const seconds = Math.floor(ms / 1000)
      const minutes = Math.floor(seconds / 60)
      const hours = Math.floor(minutes / 60)
      const days = Math.floor(hours / 24)

      if (days > 0) return `${days}天${hours % 24}小时`
      if (hours > 0) return `${hours}小时${minutes % 60}分钟`
      if (minutes > 0) return `${minutes}分钟${seconds % 60}秒`
      return `${seconds}秒`
    },

    formatPercent(value, decimals = 4) {
      return api.formatPercent(value, decimals)
    },

    formatPrice(price, decimals = 2) {
      return api.formatPrice(price, decimals)
    },

    formatVolume(volume) {
      if (!volume) return '0'
      const num = parseFloat(volume)
      if (num >= 1000) {
        return (num / 1000).toFixed(1) + 'K'
      }
      return num.toFixed(1)
    },

    // 样式类获取函数
    getRiskType(riskLevel) {
      switch (riskLevel) {
        case '高': return 'danger'
        case '中': return 'warning'
        case '低': return 'success'
        default: return 'info'
      }
    },

    getMarketStatusClass(status) {
      if (status.includes('震荡')) return 'ranging'
      if (status.includes('趋势')) return 'trending'
      if (status.includes('平衡')) return 'balanced'
      return 'unknown'
    },

    getSignalType(signal) {
      return api.getSignalClass(signal)
    },

    getSignalStrengthClass(strength) {
      return strength === '强烈' ? 'strong' : 'normal'
    }
  }
}
</script>

<style scoped>
.monitor-container {
  padding: 20px;
  background: #f5f7fa;
  min-height: 100vh;
  font-family: 'Helvetica Neue', Arial, sans-serif;
}

/* 头部样式 */
.header {
  margin-bottom: 24px;
}

.title-section h1 {
  font-size: 24px;
  color: #303133;
  margin-bottom: 12px;
  font-weight: 600;
}

.status-bar {
  display: flex;
  align-items: center;
  gap: 16px;
}

.update-time {
  color: #909399;
  font-size: 14px;
}

.refresh-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #409eff;
  cursor: pointer;
  font-size: 14px;
}

.refresh-btn:hover {
  color: #66b1ff;
}

.refresh-btn .refreshing {
  animation: rotating 2s linear infinite;
}

@keyframes rotating {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 主要内容布局 */
.main-content {
  display: flex;
  gap: 24px;
  margin-bottom: 24px;
}

.left-panel {
  flex: 2;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.right-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 400px;
}

/* 卡片通用样式 */
.card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  overflow: hidden;
}

.card-header {
  padding: 16px 20px;
  background: #fafbfc;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-body {
  padding: 20px;
}

/* 市场概览 */
.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.overview-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.overview-item label {
  font-size: 12px;
  color: #909399;
}

.overview-item span {
  font-size: 14px;
  font-weight: 500;
}

.overview-item span.warning {
  color: #e6a23c;
}

.overview-item span.danger {
  color: #f56c6c;
}

.overview-item span.normal {
  color: #67c23a;
}

/* 价格网格 */
.price-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.price-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.price-item:last-child {
  border-bottom: none;
}

.price-item label {
  color: #606266;
  font-size: 13px;
}

.price-item .price {
  font-weight: 600;
  font-size: 14px;
}

.price-item .price.high {
  color: #f56c6c;
}

.price-item .price.low {
  color: #67c23a;
}

.price-item .up {
  color: #f56c6c;
  font-weight: 600;
}

.price-item .down {
  color: #67c23a;
  font-weight: 600;
}

/* 策略信息 */
.strategy-info {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  padding: 12px;
  background: #f9f9f9;
  border-radius: 8px;
  margin-top: 16px;
}

.strategy-param {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}

.strategy-param label {
  color: #909399;
}

.strategy-param span {
  color: #303133;
  font-weight: 500;
}

/* 信号分析 */
.signal-analysis {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}

.analysis-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}

.analysis-item label {
  color: #606266;
  font-size: 14px;
}

.analysis-item span {
  font-weight: 500;
}

.analysis-item span.confirm {
  color: #67c23a;
}

.analysis-item span.no-confirm {
  color: #f56c6c;
}

.analysis-item span.recommended-action {
  color: #409eff;
  font-weight: 600;
}

.analysis-item span.recommended-position {
  color: #e6a23c;
  font-weight: 600;
}

.analysis-item span.strong {
  color: #f56c6c;
  font-weight: 600;
}

.analysis-item span.normal {
  color: #909399;
}

/* 操作按钮 */
.action-buttons {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.action-buttons .el-button {
  flex: 1;
  padding: 12px 0;
}

/* 持仓信息 */
.position-info {
  padding: 16px;
  background: #f9f9f9;
  border-radius: 8px;
  border: 1px solid #ebeef5;
}

.position-info h4 {
  margin: 0 0 12px 0;
  color: #303133;
  font-size: 14px;
  font-weight: 600;
}

.position-details {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
}

.detail-item label {
  color: #909399;
}

.detail-item span {
  font-weight: 500;
}

.detail-item span.long {
  color: #67c23a;
}

.detail-item span.short {
  color: #f56c6c;
}

.detail-item span.profit {
  color: #67c23a;
}

.detail-item span.loss {
  color: #f56c6c;
}

.detail-item span.stop-loss {
  color: #f56c6c;
  font-weight: 600;
}

.detail-item span.take-profit {
  color: #67c23a;
  font-weight: 600;
}

/* 回测结果 */
.backtest-result {
  padding: 12px;
  background: #f9f9f9;
  border-radius: 8px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.result-header h4 {
  margin: 0;
  color: #303133;
  font-size: 14px;
  font-weight: 600;
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.result-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.result-item label {
  font-size: 12px;
  color: #909399;
}

.result-item span {
  font-size: 14px;
  font-weight: 500;
}

.result-item span.profit {
  color: #67c23a;
}

.result-item span.loss {
  color: #f56c6c;
}

.result-item span.drawdown {
  color: #e6a23c;
}

.result-item span.good {
  color: #67c23a;
}

.result-item span.bad {
  color: #f56c6c;
}

.no-result {
  text-align: center;
  padding: 40px 20px;
  color: #909399;
}

/* 信号历史 */
.history-list {
  max-height: 300px;
  overflow-y: auto;
}

.history-item {
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}

.history-item:last-child {
  border-bottom: none;
}

.history-item .time {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.history-item .signal {
  margin-bottom: 4px;
}

.history-item .details {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #606266;
}

/* 底部统计 */
.footer {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}

.statistics {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 20px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px;
  background: #f9f9f9;
  border-radius: 8px;
}

.stat-item label {
  font-size: 12px;
  color: #909399;
}

.stat-item span {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.stat-item span.good {
  color: #67c23a;
}

.stat-item span.bad {
  color: #f56c6c;
}

.stat-item span.profit {
  color: #67c23a;
}

.stat-item span.loss {
  color: #f56c6c;
}

.stat-item span.drawdown {
  color: #e6a23c;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .main-content {
    flex-direction: column;
  }

  .right-panel {
    min-width: auto;
  }

  .overview-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .statistics {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 768px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }

  .price-grid {
    grid-template-columns: 1fr;
  }

  .strategy-info {
    grid-template-columns: 1fr;
  }

  .position-details {
    grid-template-columns: 1fr;
  }

  .result-grid {
    grid-template-columns: 1fr;
  }

  .statistics {
    grid-template-columns: repeat(2, 1fr);
  }

  .action-buttons {
    flex-direction: column;
  }
}
</style>
