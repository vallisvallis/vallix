<template>
  <div class="prediction-analysis-container">
    <!-- 头部标题 -->
    <div class="header">
      <div class="title-section">
        <h1>BTC量化特征工程分析</h1>
        <p class="subtitle">基于价格、量能、动量等多维度特征的5分钟价格预测</p>
        <div class="status-bar">
          <span class="update-time">最后更新: {{ formatTime(updateTime) }}</span>
          <span class="refresh-btn" @click="loadRealtimePredict">
            <i class="el-icon-view"></i>
            实时预测
          </span>
          <span class="refresh-btn" @click="loadAnalysis">
            <i class="el-icon-refresh" :class="{ 'refreshing': loading }"></i>
            回测分析
          </span>
        </div>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-container">
      <i class="el-icon-loading"></i>
      <span>正在执行特征工程回测...</span>
    </div>

    <!-- 分析结果 -->
    <div v-else-if="analysisData" class="analysis-content">
      <!-- 基本信息 -->
      <div class="card info-card">
        <div class="card-header">
          <h3><i class="el-icon-data-analysis"></i> 回测概况</h3>
        </div>
        <div class="card-body">
          <div class="info-grid">
            <div class="info-item">
              <span class="label">总样本数:</span>
              <span class="value">{{ analysisData.totalSamples }}</span>
            </div>
            <div class="info-item">
              <span class="label">有效样本数:</span>
              <span class="value highlight">{{ analysisData.validSamples }}</span>
            </div>
            <div class="info-item">
              <span class="label">有效率:</span>
              <span class="value">{{ (analysisData.validSamples / analysisData.totalSamples * 100).toFixed(2) }}%</span>
            </div>
            <div class="info-item">
              <span class="label">分析时间:</span>
              <span class="value">{{ formatTime(analysisData.timestamp) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 单因子表现 -->
      <div class="card">
        <div class="card-header">
          <h3><i class="el-icon-s-data"></i> 单因子预测表现</h3>
        </div>
        <div class="card-body">
          <el-row :gutter="20">
            <el-col :xs="24" :sm="12" :md="8" v-for="(factor, key) in analysisData.singleFactors" :key="key">
              <div class="factor-card">
                <div class="factor-header" :class="getFactorClass(key)">
                  <h4>{{ factor.factorName }}</h4>
                </div>
                <div class="factor-stats">
                  <div class="stat-row">
                    <span class="stat-label">预测次数:</span>
                    <span class="stat-value">{{ factor.totalPredictions }}</span>
                  </div>
                  <div class="stat-row main-stat">
                    <span class="stat-label">整体胜率:</span>
                    <span class="stat-value accuracy">{{ factor.accuracy }}%</span>
                  </div>
                  <div class="sub-stats">
                    <div class="sub-stat up">
                      <span>多头预测:</span>
                      <strong>{{ factor.longPredictions }}</strong>
                      <span class="percent">胜率 {{ factor.longAccuracy }}%</span>
                    </div>
                    <div class="sub-stat down">
                      <span>空头预测:</span>
                      <strong>{{ factor.shortPredictions }}</strong>
                      <span class="percent">胜率 {{ factor.shortAccuracy }}%</span>
                    </div>
                  </div>
                </div>
              </div>
            </el-col>
          </el-row>
        </div>
      </div>

      <!-- 组合策略表现 -->
      <div class="card">
        <div class="card-header">
          <h3><i class="el-icon-trophy"></i> 多因子组合策略表现</h3>
        </div>
        <div class="card-body">
          <el-row :gutter="20">
            <el-col :xs="24" :sm="12" :md="8" v-for="(combo, key) in analysisData.comboStrategies" :key="key">
              <div class="combo-card" :class="getComboClass(key)">
                <div class="combo-header">
                  <h4>{{ combo.factorName }}</h4>
                  <div class="combo-badge" v-if="parseFloat(combo.accuracy) >= 70">高胜率</div>
                </div>
                <div class="combo-stats">
                  <div class="big-stat">
                    <div class="stat-label">预测次数</div>
                    <div class="stat-number">{{ combo.totalPredictions }}</div>
                  </div>
                  <div class="big-stat accuracy-display">
                    <div class="stat-label">整体胜率</div>
                    <div class="stat-number">{{ combo.accuracy }}%</div>
                  </div>
                  <div class="combo-detail">
                    <div class="detail-item">
                      <span>多头:</span>
                      <strong>{{ combo.longPredictions }}次</strong>
                      <span class="rate">{{ combo.longAccuracy }}%</span>
                    </div>
                    <div class="detail-item">
                      <span>空头:</span>
                      <strong>{{ combo.shortPredictions }}次</strong>
                      <span class="rate">{{ combo.shortAccuracy }}%</span>
                    </div>
                  </div>
                </div>
              </div>
            </el-col>
          </el-row>
        </div>
      </div>

      <!-- 最佳策略推荐 -->
      <div class="card recommendation-card">
        <div class="card-header">
          <h3><i class="el-icon-star-on"></i> 策略推荐</h3>
        </div>
        <div class="card-body">
          <div class="recommendation-content">
            <div class="best-strategy" v-if="getBestStrategy()">
              <div class="strategy-title">🏆 最佳策略</div>
              <div class="strategy-name">{{ getBestStrategy().name }}</div>
              <div class="strategy-stats">
                <div class="stat-item">
                  <span>胜率:</span>
                  <strong class="highlight">{{ getBestStrategy().accuracy }}%</strong>
                </div>
                <div class="stat-item">
                  <span>预测次数:</span>
                  <strong>{{ getBestStrategy().predictions }}次</strong>
                </div>
              </div>
            </div>

            <div class="recommendations">
              <h4>💡 使用建议:</h4>
              <ul class="tips-list">
                <li>单因子适合快速判断，组合策略胜率更高但信号较少</li>
                <li>强共振组合（组合3）虽然信号少，但理论胜率最高</li>
                <li>建议结合多个时间周期和因子进行综合判断</li>
                <li>注意市场波动性变化对策略有效性的影响</li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 实时预测结果 -->
    <div v-else-if="showMode === 'realtime' && realtimeData" class="analysis-content">
      <!-- 当前价格和特征 -->
      <div class="card realtime-card">
        <div class="card-header">
          <h3><i class="el-icon-current-location"></i> 实时预测</h3>
        </div>
        <div class="card-body">
          <div class="realtime-info">
            <div class="price-display">
              <div class="price-label">当前BTC价格</div>
              <div class="price-value">${{ realtimeData.currentPrice.toFixed(2) }}</div>
              <div class="price-time">{{ formatTime(realtimeData.timestamp) }}</div>
            </div>

            <div class="features-grid">
              <div class="feature-item">
                <span class="feature-label">RSI(14)</span>
                <span class="feature-value" :class="getRsiClass(realtimeData.currentFeatures.rsi)">
                  {{ realtimeData.currentFeatures.rsi }}
                </span>
              </div>
              <div class="feature-item">
                <span class="feature-label">EMA9</span>
                <span class="feature-value">${{ realtimeData.currentFeatures.ema9 }}</span>
              </div>
              <div class="feature-item">
                <span class="feature-label">EMA21</span>
                <span class="feature-value">${{ realtimeData.currentFeatures.ema21 }}</span>
              </div>
              <div class="feature-item">
                <span class="feature-label">量比</span>
                <span class="feature-value">{{ realtimeData.currentFeatures.volRatio }}</span>
              </div>
              <div class="feature-item">
                <span class="feature-label">5分位</span>
                <span class="feature-value">{{ realtimeData.currentFeatures.position5m }}</span>
              </div>
            </div>
          </div>

          <div class="recommendation-banner" :class="getRecommendationClass(realtimeData.recommendation)">
            <div class="rec-icon">🎯</div>
            <div class="rec-text">
              <div class="rec-title">综合建议</div>
              <div class="rec-content">{{ realtimeData.recommendation }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 组合策略预测 -->
      <div class="card">
        <div class="card-header">
          <h3><i class="el-icon-s-operation"></i> 策略预测结果</h3>
        </div>
        <div class="card-body">
          <el-row :gutter="20">
            <el-col :xs="24" :sm="12" :md="8" v-for="(pred, key) in realtimeData.combos" :key="key">
              <div class="prediction-card" :class="getPredictionClass(pred)">
                <div class="pred-header">{{ getComboName(key) }}</div>
                <div class="pred-body">
                  <div class="pred-signal" v-if="pred !== null">
                    <i class="pred-icon" :class="pred === 1 ? 'el-icon-top' : 'el-icon-bottom'"></i>
                    <span class="pred-direction">{{ pred === 1 ? '看涨' : '看跌' }}</span>
                  </div>
                  <div class="pred-signal neutral" v-else>
                    <i class="el-icon-minus"></i>
                    <span class="pred-direction">观望</span>
                  </div>
                </div>
              </div>
            </el-col>
          </el-row>
        </div>
      </div>

      <!-- 单因子预测 -->
      <div class="card">
        <div class="card-header">
          <h3><i class="el-icon-s-flag"></i> 单因子预测</h3>
        </div>
        <div class="card-body">
          <div class="factor-predictions">
            <div class="factor-pred-item" v-for="(pred, key) in realtimeData.singleFactors" :key="key">
              <span class="factor-name">{{ getFactorName(key) }}</span>
              <span class="pred-badge" :class="getPredBadgeClass(pred)">
                {{ pred === 1 ? '涨' : (pred === 0 ? '跌' : '-') }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 错误提示 -->
    <div v-else-if="error" class="error-container">
      <i class="el-icon-warning"></i>
      <span>{{ error }}</span>
      <el-button type="primary" @click="loadAnalysis">重试</el-button>
    </div>
  </div>
</template>

<script>
import * as api from '@/api/tool/btcusdt/btcusdt'

export default {
  name: 'BtcFeatureEngineering',
  data() {
    return {
      loading: false,
      updateTime: null,
      analysisData: null,
      realtimeData: null,
      error: null,
      showMode: 'backtest' // 'backtest' 或 'realtime'
    }
  },

  mounted() {
    this.loadAnalysis()
  },

  methods: {
    async loadAnalysis() {
      try {
        this.loading = true
        this.error = null
        this.showMode = 'backtest'

        console.log('开始加载BTC特征工程回测数据...')

        // 减少数据点到500，加快计算速度
        const response = await api.backtestWithFeatures(500, 5)

        console.log('BTC特征工程回测响应:', response)

        if (response && response.code === 200 && response.data) {
          this.analysisData = response.data
          this.updateTime = Date.now()

          console.log('BTC特征工程回测完成')
          this.$message.success('回测完成！')
        } else {
          throw new Error(response.msg || '获取回测数据失败')
        }
      } catch (error) {
        console.error('加载BTC特征工程回测数据失败:', error)
        this.error = error.message || '加载失败，请重试'
        this.$message.error(this.error)
      } finally {
        this.loading = false
      }
    },

    async loadRealtimePredict() {
      try {
        this.loading = true
        this.error = null
        this.showMode = 'realtime'

        console.log('开始BTC实时预测...')

        const response = await api.realtimePredict()

        console.log('BTC实时预测响应:', response)

        if (response && response.code === 200 && response.data) {
          this.realtimeData = response.data
          this.updateTime = Date.now()

          console.log('BTC实时预测完成')
          this.$message.success('实时预测完成！')
        } else {
          throw new Error(response.msg || '获取预测数据失败')
        }
      } catch (error) {
        console.error('BTC实时预测失败:', error)
        this.error = error.message || '预测失败，请重试'
        this.$message.error(this.error)
      } finally {
        this.loading = false
      }
    },

    getFactorClass(key) {
      const classes = {
        inertia: 'factor-inertia',
        position: 'factor-position',
        ma: 'factor-ma',
        rsi: 'factor-rsi',
        volume: 'factor-volume'
      }
      return classes[key] || ''
    },

    getComboClass(key) {
      if (key.includes('combo3')) return 'combo-strong'
      if (key.includes('combo2')) return 'combo-micro'
      return 'combo-technical'
    },

    getBestStrategy() {
      if (!this.analysisData || !this.analysisData.comboStrategies) {
        return null
      }

      let bestStrategy = null
      let bestAccuracy = 0

      // 检查所有组合策略
      Object.entries(this.analysisData.comboStrategies).forEach(([key, combo]) => {
        const accuracy = parseFloat(combo.accuracy)
        if (accuracy > bestAccuracy && combo.totalPredictions > 10) {
          bestAccuracy = accuracy
          bestStrategy = {
            name: combo.factorName,
            accuracy: combo.accuracy,
            predictions: combo.totalPredictions
          }
        }
      })

      // 如果没有合适的组合策略，检查单因子
      if (!bestStrategy && this.analysisData.singleFactors) {
        Object.entries(this.analysisData.singleFactors).forEach(([key, factor]) => {
          const accuracy = parseFloat(factor.accuracy)
          if (accuracy > bestAccuracy && factor.totalPredictions > 50) {
            bestAccuracy = accuracy
            bestStrategy = {
              name: factor.factorName,
              accuracy: factor.accuracy,
              predictions: factor.totalPredictions
            }
          }
        })
      }

      return bestStrategy
    },

    formatTime(timestamp) {
      if (!timestamp) return '-'
      const date = new Date(timestamp)
      return date.toLocaleString('zh-CN')
    },

    getRsiClass(rsi) {
      const value = parseFloat(rsi)
      if (value > 70) return 'rsi-overbought'
      if (value < 30) return 'rsi-oversold'
      return 'rsi-neutral'
    },

    getRecommendationClass(recommendation) {
      if (recommendation.includes('强烈看多')) return 'rec-strong-bullish'
      if (recommendation.includes('温和看多')) return 'rec-mild-bullish'
      if (recommendation.includes('强烈看空')) return 'rec-strong-bearish'
      if (recommendation.includes('温和看空')) return 'rec-mild-bearish'
      return 'rec-neutral'
    },

    getPredictionClass(pred) {
      if (pred === 1) return 'pred-bullish'
      if (pred === 0) return 'pred-bearish'
      return 'pred-neutral'
    },

    getComboName(key) {
      const names = {
        combo1_technical: '技术面组合',
        combo2_micro: '微观组合',
        combo3_strong: '强共振',
        combo4_trend_strict: '趋势跟踪★',
        combo5_reversion_strict: '均值回归★',
        combo6_momentum_strict: '动量突破★',
        combo7_multi_resonance: '多重共振★★'
      }
      return names[key] || key
    },

    getFactorName(key) {
      const names = {
        inertia: '价格惯性',
        position: '区间位置',
        ma: '均线状态',
        rsi: 'RSI',
        volume: '量价配合'
      }
      return names[key] || key
    },

    getPredBadgeClass(pred) {
      if (pred === 1) return 'badge-up'
      if (pred === 0) return 'badge-down'
      return 'badge-neutral'
    }
  }
}
</script>

<style scoped>
.prediction-analysis-container {
  padding: 20px;
  background: #f5f7fa;
  min-height: 100vh;
  font-family: 'Helvetica Neue', Arial, sans-serif;
}

.header {
  margin-bottom: 24px;
}

.title-section h1 {
  font-size: 24px;
  color: #303133;
  margin-bottom: 8px;
  font-weight: 600;
}

.subtitle {
  color: #909399;
  font-size: 14px;
  margin-bottom: 12px;
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

.loading-container,
.error-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #909399;
  font-size: 16px;
}

.loading-container i,
.error-container i {
  font-size: 48px;
  margin-bottom: 16px;
}

.error-container {
  color: #f56c6c;
}

.card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  overflow: hidden;
  margin-bottom: 24px;
}

.card-header {
  padding: 16px 20px;
  background: #fafbfc;
  border-bottom: 1px solid #ebeef5;
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

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  background: #f9f9f9;
  border-radius: 8px;
}

.info-item .label {
  color: #606266;
  font-size: 14px;
}

.info-item .value {
  color: #303133;
  font-weight: 600;
  font-size: 14px;
}

.info-item .value.highlight {
  color: #409eff;
  font-size: 16px;
}

/* 因子卡片样式 */
.factor-card {
  background: white;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  margin-bottom: 16px;
  transition: all 0.3s ease;
}

.factor-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

.factor-header {
  padding: 12px 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.factor-header h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.factor-inertia .factor-header {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
}

.factor-position .factor-header {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
}

.factor-ma .factor-header {
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
}

.factor-rsi .factor-header {
  background: linear-gradient(135deg, #fa709a 0%, #fee140 100%);
}

.factor-volume .factor-header {
  background: linear-gradient(135deg, #30cfd0 0%, #330867 100%);
}

.factor-stats {
  padding: 16px;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed #e4e7ed;
}

.stat-row:last-child {
  border-bottom: none;
}

.stat-row.main-stat {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  margin: 12px 0;
}

.stat-label {
  color: #606266;
  font-size: 13px;
}

.stat-value {
  color: #303133;
  font-weight: 600;
  font-size: 14px;
}

.stat-value.accuracy {
  color: #67c23a;
  font-size: 18px;
}

.sub-stats {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.sub-stat {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
}

.sub-stat.up {
  background: #f0f9ff;
  color: #67c23a;
}

.sub-stat.down {
  background: #fef0f0;
  color: #f56c6c;
}

.sub-stat strong {
  margin: 0 8px;
}

.sub-stat .percent {
  font-weight: 600;
}

/* 组合策略卡片 */
.combo-card {
  background: white;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 3px 10px rgba(0, 0, 0, 0.08);
  margin-bottom: 16px;
  transition: all 0.3s ease;
}

.combo-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.15);
}

.combo-header {
  padding: 14px 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.combo-header h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.combo-badge {
  background: #ffd700;
  color: #333;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}

.combo-strong .combo-header {
  background: linear-gradient(135deg, #ff6b6b 0%, #ee5a6f 100%);
}

.combo-micro .combo-header {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
}

.combo-technical .combo-header {
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
}

.combo-stats {
  padding: 16px;
}

.big-stat {
  text-align: center;
  padding: 12px;
  margin-bottom: 12px;
  background: #f5f7fa;
  border-radius: 8px;
}

.big-stat .stat-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.big-stat .stat-number {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}

.big-stat.accuracy-display .stat-number {
  color: #67c23a;
}

.combo-detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  background: #fafbfc;
  border-radius: 6px;
  font-size: 12px;
}

.detail-item .rate {
  color: #67c23a;
  font-weight: 600;
}

/* 推荐卡片 */
.recommendation-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.recommendation-card .card-header {
  background: rgba(255, 255, 255, 0.1);
  border-bottom: 1px solid rgba(255, 255, 255, 0.2);
}

.recommendation-card .card-header h3 {
  color: white;
}

.recommendation-content {
  color: white;
}

.best-strategy {
  background: rgba(255, 255, 255, 0.15);
  padding: 20px;
  border-radius: 10px;
  margin-bottom: 20px;
  text-align: center;
}

.strategy-title {
  font-size: 14px;
  margin-bottom: 8px;
  opacity: 0.9;
}

.strategy-name {
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 16px;
}

.strategy-stats {
  display: flex;
  justify-content: space-around;
}

.stat-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-item span {
  font-size: 12px;
  opacity: 0.8;
}

.stat-item strong {
  font-size: 18px;
}

.stat-item strong.highlight {
  color: #ffd700;
}

.recommendations h4 {
  margin: 0 0 12px 0;
  font-size: 16px;
}

.tips-list {
  margin: 0;
  padding-left: 20px;
}

.tips-list li {
  margin-bottom: 8px;
  line-height: 1.6;
  font-size: 14px;
}

/* 实时预测样式 */
.realtime-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.realtime-card .card-header {
  background: rgba(255, 255, 255, 0.1);
  border-bottom: 1px solid rgba(255, 255, 255, 0.2);
}

.realtime-card .card-header h3 {
  color: white;
}

.realtime-info {
  color: white;
}

.price-display {
  text-align: center;
  padding: 20px;
  margin-bottom: 20px;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 12px;
}

.price-label {
  font-size: 14px;
  opacity: 0.9;
  margin-bottom: 8px;
}

.price-value {
  font-size: 36px;
  font-weight: 700;
  margin-bottom: 8px;
}

.price-time {
  font-size: 12px;
  opacity: 0.7;
}

.features-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.feature-item {
  background: rgba(255, 255, 255, 0.1);
  padding: 12px;
  border-radius: 8px;
  text-align: center;
}

.feature-label {
  display: block;
  font-size: 12px;
  opacity: 0.8;
  margin-bottom: 4px;
}

.feature-value {
  display: block;
  font-size: 16px;
  font-weight: 600;
}

.rsi-overbought {
  color: #ffd700;
}

.rsi-oversold {
  color: #00ff88;
}

.rsi-neutral {
  color: white;
}

.recommendation-banner {
  padding: 20px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  gap: 16px;
  background: rgba(255, 255, 255, 0.2);
}

.rec-icon {
  font-size: 40px;
}

.rec-text {
  flex: 1;
}

.rec-title {
  font-size: 14px;
  opacity: 0.9;
  margin-bottom: 4px;
}

.rec-content {
  font-size: 18px;
  font-weight: 700;
}

.prediction-card {
  background: white;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  margin-bottom: 16px;
  transition: all 0.3s ease;
}

.prediction-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.pred-header {
  padding: 12px 16px;
  background: #f5f7fa;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.pred-body {
  padding: 20px;
  text-align: center;
}

.pred-signal {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.pred-icon {
  font-size: 32px;
}

.pred-bullish .pred-icon {
  color: #67c23a;
}

.pred-bearish .pred-icon {
  color: #f56c6c;
}

.pred-neutral .pred-icon {
  color: #909399;
}

.pred-direction {
  font-size: 18px;
  font-weight: 700;
}

.pred-bullish .pred-direction {
  color: #67c23a;
}

.pred-bearish .pred-direction {
  color: #f56c6c;
}

.pred-neutral .pred-direction {
  color: #909399;
}

.factor-predictions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.factor-pred-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 8px;
}

.factor-name {
  font-size: 14px;
  color: #606266;
}

.pred-badge {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
}

.badge-up {
  background: #f0f9ff;
  color: #67c23a;
}

.badge-down {
  background: #fef0f0;
  color: #f56c6c;
}

.badge-neutral {
  background: #f4f4f5;
  color: #909399;
}

@media (max-width: 768px) {
  .features-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .factor-predictions {
    grid-template-columns: 1fr;
  }
}
</style>