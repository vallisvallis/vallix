<!-- D:\vallix\d3code\d3code-ui\src\views\vallisUsdt\ethHistory.vue -->
<template>
  <div class="container-fluid">
    <!-- 页面标题 -->
    <div class="row mb-4">
      <div class="col-12">
        <div class="page-header">
          <div class="d-flex align-items-center justify-between">
            <div>
              <h1 class="page-title">
                <i class="fab fa-ethereum text-purple mr-2"></i>
                ETH/USDT 历史秒级数据
              </h1>
              <p class="page-subtitle">实时监控以太坊价格波动，数据保留最近一个月</p>
            </div>
            <div class="d-flex align-items-center">
              <button 
                @click="loadData" 
                class="btn btn-primary btn-icon mr-2" 
                :disabled="loading"
              >
                <i class="fas fa-sync-alt" :class="{ 'fa-spin': loading }"></i>
                <span>刷新数据</span>
              </button>
              <button 
                @click="toggleAutoRefresh" 
                class="btn btn-icon" 
                :class="autoRefresh ? 'btn-success' : 'btn-secondary'"
              >
                <i class="fas" :class="autoRefresh ? 'fa-pause-circle' : 'fa-play-circle'"></i>
                <span>{{ autoRefresh ? '暂停刷新' : '自动刷新' }}</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="row mb-4">
      <div class="col-lg-3 col-md-6 mb-4">
        <div class="stat-card bg-gradient-info">
          <div class="stat-icon">
            <i class="fas fa-database"></i>
          </div>
          <div class="stat-content">
            <span class="stat-label">总数据量</span>
            <span class="stat-value">{{ totalCount.toLocaleString() }}</span>
          </div>
        </div>
      </div>
      <div class="col-lg-3 col-md-6 mb-4">
        <div class="stat-card bg-gradient-success">
          <div class="stat-icon">
            <i class="fas fa-chart-line"></i>
          </div>
          <div class="stat-content">
            <span class="stat-label">当前价格 (USDT)</span>
            <span class="stat-value price-value">${{ currentPrice }}</span>
          </div>
        </div>
      </div>
      <div class="col-lg-3 col-md-6 mb-4">
        <div class="stat-card bg-gradient-warning">
          <div class="stat-icon">
            <i class="fas fa-clock"></i>
          </div>
          <div class="stat-content">
            <span class="stat-label">数据时间范围</span>
            <span class="stat-value small-text">{{ timeRange }}</span>
          </div>
        </div>
      </div>
      <div class="col-lg-3 col-md-6 mb-4">
        <div class="stat-card bg-gradient-danger">
          <div class="stat-icon">
            <i class="fas fa-timer"></i>
          </div>
          <div class="stat-content">
            <span class="stat-label">最后更新</span>
            <span class="stat-value small-text">{{ lastUpdateTime }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 查询条件 -->
    <div class="card filter-card mb-4">
      <div class="card-body">
        <div class="row">
          <div class="col-md-4">
            <div class="form-group">
              <label class="form-label">开始时间</label>
              <div class="input-group">
                <div class="input-group-prepend">
                  <span class="input-group-text"><i class="fas fa-calendar-alt"></i></span>
                </div>
                <input type="datetime-local" v-model="startTime" class="form-control" @change="onTimeRangeChange" />
              </div>
            </div>
          </div>
          <div class="col-md-4">
            <div class="form-group">
              <label class="form-label">结束时间</label>
              <div class="input-group">
                <div class="input-group-prepend">
                  <span class="input-group-text"><i class="fas fa-calendar-alt"></i></span>
                </div>
                <input type="datetime-local" v-model="endTime" class="form-control" @change="onTimeRangeChange" />
              </div>
            </div>
          </div>
          <div class="col-md-4">
            <div class="form-group">
              <label class="form-label">显示条数</label>
              <div class="input-group">
                <div class="input-group-prepend">
                  <span class="input-group-text"><i class="fas fa-list"></i></span>
                </div>
                <select v-model="pageSize" class="form-control" @change="loadData">
                  <option :value="100">100 条</option>
                  <option :value="500">500 条</option>
                  <option :value="1000">1,000 条</option>
                  <option :value="5000">5,000 条</option>
                </select>
              </div>
            </div>
          </div>
        </div>
        <div class="row mt-2">
          <div class="col-md-12">
            <button @click="clearTimeRange" class="btn btn-outline-secondary btn-sm">
              <i class="fas fa-times"></i> 清除时间筛选
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="card data-card">
      <div class="card-header data-card-header">
        <h4 class="card-title"><i class="fas fa-table mr-2"></i>秒级数据详情</h4>
        <div class="card-tools">
          <span class="badge badge-info">{{ totalCount.toLocaleString() }} 条记录</span>
        </div>
      </div>
      <div class="card-body p-0">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th class="sticky-col">序号</th>
                <th>时间</th>
                <th>开盘价</th>
                <th>收盘价</th>
                <th>最高价</th>
                <th>最低价</th>
                <th>成交量</th>
                <th>10分钟最高</th>
                <th>10分钟最低</th>
                <th>价格比例</th>
                <th>较10分钟前</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, index) in displayData" :key="item.timestamp" class="data-row">
                <td class="sticky-col">{{ index + 1 }}</td>
                <td class="time-col">{{ formatTime(item.timestamp) }}</td>
                <td :class="getPriceClass(item.open, item.close)">{{ item.open }}</td>
                <td :class="getPriceClass(item.close, item.open)">{{ item.close }}</td>
                <td class="high-col">{{ item.high }}</td>
                <td class="low-col">{{ item.low }}</td>
                <td class="volume-col">{{ formatVolume(item.volume) }}</td>
                <td class="high-col">{{ item.high10min }}</td>
                <td class="low-col">{{ item.low10min }}</td>
                <td>
                  <span class="price-ratio-badge" :class="getPriceRatioClass(item.priceRatio)">
                    {{ item.priceRatio }}%
                  </span>
                </td>
                <td>
                  <div class="change-indicator">
                    <span 
                      class="change-icon"
                      :class="{
                        'text-green': item.isHigherThan10MinAgo,
                        'text-red': item.isLowerThan10MinAgo,
                        'text-gray': !item.isHigherThan10MinAgo && !item.isLowerThan10MinAgo
                      }"
                    >
                      <i class="fas" :class="{
                        'fa-arrow-up': item.isHigherThan10MinAgo,
                        'fa-arrow-down': item.isLowerThan10MinAgo,
                        'fa-minus': !item.isHigherThan10MinAgo && !item.isLowerThan10MinAgo
                      }"></i>
                    </span>
                    <span 
                      class="change-value"
                      :class="{
                        'text-green font-weight-bold': item.isHigherThan10MinAgo,
                        'text-red font-weight-bold': item.isLowerThan10MinAgo,
                        'text-gray': !item.isHigherThan10MinAgo && !item.isLowerThan10MinAgo
                      }"
                    >
                      {{ item.priceChangePercent !== '-' ? item.priceChangePercent + '%' : '-' }}
                    </span>
                  </div>
                </td>
                <td>
                  <button 
                    v-if="item.targetData"
                    @click="showCompareModal(item)"
                    class="btn btn-info btn-sm compare-btn"
                  >
                    <i class="fas fa-eye"></i> 查看对比
                  </button>
                  <span v-else class="text-gray">-</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 加载状态 -->
        <div v-if="loading" class="loading-overlay">
          <div class="loading-content">
            <div class="loading-spinner"></div>
            <p>正在加载数据...</p>
          </div>
        </div>

        <!-- 空数据提示 -->
        <div v-if="!loading && ethData.length === 0" class="empty-state">
          <div class="empty-icon">
            <i class="fas fa-database"></i>
          </div>
          <h3>暂无数据</h3>
          <p>数据库中还没有历史数据，请先运行数据导入任务</p>
          <button @click="loadData" class="btn btn-primary">
            <i class="fas fa-refresh mr-2"></i>重新加载
          </button>
        </div>
      </div>

      <!-- 图表区域 -->
      <div class="chart-section">
        <div class="chart-header">
          <h3><i class="fas fa-chart-line mr-2"></i>数据可视化</h3>
          <div class="chart-tabs">
            <button @click="chartType = 'price'" :class="['chart-tab', { active: chartType === 'price' }]">
              <i class="fas fa-line-chart"></i> 价格走势
            </button>
            <button @click="chartType = 'candlestick'" :class="['chart-tab', { active: chartType === 'candlestick' }]">
              <i class="fas fa-chart-bar"></i> K线图
            </button>
            <button @click="chartType = 'volume'" :class="['chart-tab', { active: chartType === 'volume' }]">
              <i class="fas fa-bar-chart"></i> 成交量
            </button>
            <button @click="chartType = 'change'" :class="['chart-tab', { active: chartType === 'change' }]">
              <i class="fas fa-trending-up"></i> 涨跌对比
            </button>
          </div>
        </div>
        <div ref="chartRef" class="chart-container"></div>
      </div>
    </div>
  </div>

  <!-- 对比弹窗 -->
  <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
    <div class="modal-content">
      <div class="modal-header">
        <h3><i class="fas fa-exchange-alt mr-2"></i>价格对比详情</h3>
        <button @click="closeModal" class="modal-close">
          <i class="fas fa-times"></i>
        </button>
      </div>
      <div class="modal-body">
        <div class="compare-row">
          <div class="compare-item current">
            <div class="compare-label">当前数据</div>
            <div class="compare-time">{{ formatTime(modalData.current.timestamp) }}</div>
            <div class="compare-price"><span class="label">收盘价:</span><span class="value">${{ modalData.current.close }}</span></div>
            <div class="compare-price"><span class="label">开盘价:</span><span class="value">${{ modalData.current.open }}</span></div>
            <div class="compare-price"><span class="label">最高价:</span><span class="value high">${{ modalData.current.high }}</span></div>
            <div class="compare-price"><span class="label">最低价:</span><span class="value low">${{ modalData.current.low }}</span></div>
          </div>
          <div class="compare-arrow"><i class="fas fa-arrow-right"></i></div>
          <div class="compare-item target">
            <div class="compare-label">10分钟前数据</div>
            <div class="compare-time">{{ formatTime(modalData.target.timestamp) }}</div>
            <div class="compare-price"><span class="label">收盘价:</span><span class="value">${{ modalData.target.close }}</span></div>
            <div class="compare-price"><span class="label">开盘价:</span><span class="value">${{ modalData.target.open }}</span></div>
            <div class="compare-price"><span class="label">最高价:</span><span class="value high">${{ modalData.target.high }}</span></div>
            <div class="compare-price"><span class="label">最低价:</span><span class="value low">${{ modalData.target.low }}</span></div>
          </div>
        </div>
        <div class="compare-result">
          <div class="result-label">价格变化</div>
          <div class="result-value" :class="{'text-green': modalData.isHigher, 'text-red': modalData.isLower, 'text-gray': !modalData.isHigher && !modalData.isLower}">
            <i v-if="modalData.isHigher" class="fas fa-arrow-up"></i>
            <i v-else-if="modalData.isLower" class="fas fa-arrow-down"></i>
            <i v-else class="fas fa-minus"></i>
            <span> {{ modalData.priceChangePercent }}%</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { getRecentKlineWithCompare, getKlineByTimeRangeWithCompare } from '@/api/tool/vallisusdt/ethHistory'
import * as echarts from 'echarts'

// 响应式数据
const ethData = ref([])
const loading = ref(false)
const autoRefresh = ref(false)
const pageSize = ref(100)
const startTime = ref('')
const endTime = ref('')
const lastUpdateTime = ref('')

// 弹窗相关
const showModal = ref(false)
const modalData = ref({
  current: {},
  target: {},
  priceChange: '-',
  priceChangePercent: '-',
  isHigher: false,
  isLower: false
})

let refreshTimer = null
let chartInstance = null

// 计算属性
const totalCount = computed(() => ethData.value.length)

const currentPrice = computed(() => {
  if (ethData.value.length > 0) {
    // 数据是顺序排列的（旧的在前，新的在后），所以最新的在最后
    return ethData.value[ethData.value.length - 1].close || '0'
  }
  return '0'
})

const timeRange = computed(() => {
  if (ethData.value.length > 0) {
    // 数据是顺序排列的（旧的在前，新的在后）
    const first = ethData.value[0] // 最早的
    const last = ethData.value[ethData.value.length - 1] // 最新的
    return `${formatTime(first.timestamp)} - ${formatTime(last.timestamp)}`
  }
  return '-'
})

const displayData = computed(() => {
  // 使用后端返回的10分钟对比数据
  const data = ethData.value.map((item, index) => {
    const stats = calculate10MinStats(ethData.value, index)
    const changePercent = parseFloat(item.priceChangePercent) || 0
    // 查找10分钟前的数据用于弹窗显示
    const targetData = findTargetData(ethData.value, item.timestamp)
    return {
      ...item,
      high10min: stats.high10min,
      low10min: stats.low10min,
      priceRatio: stats.priceRatio,
      isHigherThan10MinAgo: changePercent > 0,
      isLowerThan10MinAgo: changePercent < 0,
      targetData: targetData
    }
  })
  return data
})

// 查找10分钟前的数据
const findTargetData = (data, currentTimestamp) => {
  const tenMinutesAgo = currentTimestamp - 600000000 // 微秒级
  return data.find(item => item.timestamp === tenMinutesAgo)
}

const showCompareModal = (item) => {
  if (item.targetData) {
    modalData.value = {
      current: item,
      target: item.targetData,
      priceChange: item.priceChange,
      priceChangePercent: item.priceChangePercent,
      isHigher: item.isHigherThan10MinAgo,
      isLower: item.isLowerThan10MinAgo
    }
    showModal.value = true
  }
}



// 方法
const formatTime = (timestamp) => {
  if (!timestamp) return '-'
  // 处理时间戳，确保是毫秒级（13位）
  // 如果是微秒级（16位）或纳秒级，转换为毫秒级
  let ts = Number(timestamp)
  if (ts > 1000000000000000) { // 微秒级（16位）
    ts = ts / 1000
  } else if (ts > 10000000000000) { // 可能是纳秒级
    ts = ts / 1000000
  }
  const date = new Date(ts)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

const toNumber = (value) => {
  if (value === null || value === undefined) return 0
  return parseFloat(value) || 0
}

const calculate10MinStats = (data, currentIndex) => {
  const minutes10Ago = 600 // 10分钟 = 600秒
  const startIndex = Math.max(0, currentIndex - minutes10Ago)
  const sliceData = data.slice(startIndex, currentIndex + 1)

  if (sliceData.length === 0) {
    return { high10min: '-', low10min: '-', priceRatio: '-' }
  }

  let high10min = toNumber(sliceData[0].high)
  let low10min = toNumber(sliceData[0].low)

  for (let i = 1; i < sliceData.length; i++) {
    high10min = Math.max(high10min, toNumber(sliceData[i].high))
    low10min = Math.min(low10min, toNumber(sliceData[i].low))
  }

  const currentClose = toNumber(data[currentIndex].close)
  let priceRatio = '-'

  if (high10min !== low10min) {
    priceRatio = (((currentClose - low10min) / (high10min - low10min)) * 100).toFixed(2)
  }

  return {
    high10min: high10min.toFixed(2),
    low10min: low10min.toFixed(2),
    priceRatio
  }
}

const getPriceRatioClass = (ratio) => {
  if (ratio === '-') return 'ratio-neutral'
  const value = parseFloat(ratio)
  if (value > 80) return 'ratio-high'
  if (value < 20) return 'ratio-low'
  return 'ratio-neutral'
}

const getPriceClass = (current, previous) => {
  const currentNum = toNumber(current)
  const previousNum = toNumber(previous)
  if (currentNum > previousNum) return 'price-up'
  if (currentNum < previousNum) return 'price-down'
  return ''
}

const formatVolume = (volume) => {
  if (!volume) return '-'
  const num = parseFloat(volume)
  if (isNaN(num)) return volume
  // 去除末尾多余的零
  let formatted = num.toFixed(8).replace(/\.?0+$/, '')
  // 如果数字很大，使用K/M单位
  if (num >= 1000000) {
    formatted = (num / 1000000).toFixed(2) + 'M'
  } else if (num >= 1000) {
    formatted = (num / 1000).toFixed(2) + 'K'
  }
  return formatted
}

const clearTimeRange = () => {
  startTime.value = ''
  endTime.value = ''
  loadData()
}

const loadData = async () => {
  loading.value = true
  try {
    if (startTime.value && endTime.value) {
      // 根据时间范围查询
      const startTs = new Date(startTime.value).getTime()
      const endTs = new Date(endTime.value).getTime()
      const response = await getKlineByTimeRangeWithCompare(startTs, endTs)
      if (response.code === 200) {
        ethData.value = response.data // 保持顺序（旧的在前，新的在后）
      }
    } else {
      // 查询最近的数据（包含10分钟对比）
      const response = await getRecentKlineWithCompare(pageSize.value)
      if (response.code === 200) {
        ethData.value = response.data // 保持顺序（旧的在前，新的在后）
      }
    }
    lastUpdateTime.value = new Date().toLocaleString('zh-CN')
    nextTick(() => updateChart())
  } catch (error) {
    console.error('加载数据失败:', error)
  } finally {
    loading.value = false
  }
}

// 图表相关
const chartRef = ref(null)
const chartType = ref('price')

const initChart = () => {
  if (!chartRef.value) return
  chartInstance = echarts.init(chartRef.value)
  updateChart()
}

const updateChart = () => {
  if (!chartInstance || ethData.value.length === 0) return
  const option = getChartOption()
  chartInstance.setOption(option)
}

const getChartOption = () => {
  const data = ethData.value
  const times = data.map(item => formatTime(item.timestamp))
  const opens = data.map(item => parseFloat(item.open) || 0)
  const closes = data.map(item => parseFloat(item.close) || 0)
  const highs = data.map(item => parseFloat(item.high) || 0)
  const lows = data.map(item => parseFloat(item.low) || 0)
  const volumes = data.map(item => parseFloat(item.volume) || 0)
  const changes = data.map(item => {
    if (item.priceChangePercent === '-') return 0
    return parseFloat(item.priceChangePercent) || 0
  })

  switch (chartType.value) {
    case 'price':
      return {
        tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
        legend: { data: ['开盘价', '收盘价', '最高价', '最低价'] },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: { type: 'category', data: times, axisLabel: { rotate: 45, fontSize: 10 } },
        yAxis: { type: 'value', scale: true },
        series: [
          { name: '开盘价', type: 'line', data: opens, smooth: true, lineStyle: { color: '#20c997' } },
          { name: '收盘价', type: 'line', data: closes, smooth: true, lineStyle: { color: '#667eea' } },
          { name: '最高价', type: 'line', data: highs, smooth: true, lineStyle: { color: '#dc3545', type: 'dashed' } },
          { name: '最低价', type: 'line', data: lows, smooth: true, lineStyle: { color: '#28a745', type: 'dashed' } }
        ]
      }
    case 'candlestick': {
      const klineData = data.map(item => [
        parseFloat(item.open) || 0, parseFloat(item.close) || 0,
        parseFloat(item.low) || 0, parseFloat(item.high) || 0
      ])
      return {
        tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: { type: 'category', data: times, axisLabel: { rotate: 45, fontSize: 10 } },
        yAxis: { type: 'value', scale: true },
        series: [{ name: 'K线', type: 'candlestick', data: klineData,
          itemStyle: { color: '#20c997', color0: '#dc3545', borderColor: '#20c997', borderColor0: '#dc3545' }
        }]
      }
    }
    case 'volume':
      return {
        tooltip: { trigger: 'axis' },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: { type: 'category', data: times, axisLabel: { rotate: 45, fontSize: 10 } },
        yAxis: { type: 'value', scale: true },
        series: [{ name: '成交量', type: 'bar', data: volumes,
          itemStyle: { color: (p) => closes[p.dataIndex] >= opens[p.dataIndex] ? '#20c997' : '#dc3545' }
        }]
      }
    case 'change':
      return {
        tooltip: { trigger: 'axis' },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: { type: 'category', data: times, axisLabel: { rotate: 45, fontSize: 10 } },
        yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
        series: [{ name: '涨跌幅', type: 'bar', data: changes,
          itemStyle: { color: (p) => p.value >= 0 ? '#20c997' : '#dc3545' }
        }]
      }
    default:
      return {}
  }
}

const handleResize = () => chartInstance?.resize()

watch(chartType, () => updateChart())

watch(() => ethData.value.length, () => {
  nextTick(() => updateChart())
})

const onTimeRangeChange = () => {
  if (startTime.value && endTime.value) {
    loadData()
  }
}

const toggleAutoRefresh = () => {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) {
    startAutoRefresh()
  } else {
    stopAutoRefresh()
  }
}

const startAutoRefresh = () => {
  stopAutoRefresh()
  refreshTimer = setInterval(() => {
    loadData()
  }, 10000) // 每10秒刷新一次
}

const stopAutoRefresh = () => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

// 生命周期
onMounted(() => {
  loadData()
  nextTick(() => {
    initChart()
    window.addEventListener('resize', handleResize)
  })
})

onUnmounted(() => {
  stopAutoRefresh()
  window.removeEventListener('resize', handleResize)
  chartInstance?.dispose()
})
</script>

<style scoped>
/* 页面标题 */
.page-header {
  padding: 20px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  color: white;
  margin-bottom: 0;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 600;
  margin-bottom: 4px;
}

.page-subtitle {
  font-size: 0.9rem;
  opacity: 0.9;
  margin: 0;
}

.btn-icon {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 统计卡片 */
.stat-card {
  position: relative;
  padding: 20px;
  border-radius: 12px;
  color: white;
  overflow: hidden;
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.stat-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 24px rgba(0, 0, 0, 0.15);
}

.stat-card::before {
  content: '';
  position: absolute;
  top: -50%;
  right: -50%;
  width: 100%;
  height: 100%;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 50%;
}

.stat-icon {
  font-size: 2rem;
  margin-bottom: 12px;
  position: relative;
  z-index: 1;
}

.stat-content {
  position: relative;
  z-index: 1;
}

.stat-label {
  display: block;
  font-size: 0.85rem;
  opacity: 0.9;
  margin-bottom: 4px;
}

.stat-value {
  display: block;
  font-size: 1.8rem;
  font-weight: 700;
}

.price-value {
  font-family: 'Courier New', monospace;
}

.small-text {
  font-size: 1rem;
}

.bg-gradient-info {
  background: linear-gradient(135deg, #00c6fb 0%, #005bea 100%);
}

.bg-gradient-success {
  background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%);
}

.bg-gradient-warning {
  background: linear-gradient(135deg, #fc4a1a 0%, #f7b733 100%);
}

.bg-gradient-danger {
  background: linear-gradient(135deg, #eb3349 0%, #f45c43 100%);
}

/* 筛选卡片 */
.filter-card {
  border: none;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  border-radius: 12px;
}

.filter-card .card-body {
  padding: 20px;
}

.form-label {
  font-weight: 500;
  margin-bottom: 6px;
}

/* 数据卡片 */
.data-card {
  border: none;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  border-radius: 12px;
  overflow: hidden;
}

.data-card-header {
  background: #f8f9fa;
  border-bottom: 1px solid #e9ecef;
  padding: 16px 20px;
}

.data-card-header .card-title {
  font-size: 1rem;
  font-weight: 600;
  margin: 0;
}

/* 表格样式 */
.table-wrapper {
  overflow-x: auto;
  max-height: 600px;
  overflow-y: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 1000px;
}

.data-table thead {
  position: sticky;
  top: 0;
  z-index: 10;
}

.data-table th {
  background: #f8f9fa;
  padding: 12px 16px;
  text-align: left;
  font-weight: 600;
  font-size: 0.85rem;
  color: #495057;
  border-bottom: 2px solid #e9ecef;
  white-space: nowrap;
}

.data-table td {
  padding: 10px 16px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 0.875rem;
  transition: background-color 0.2s ease;
}

.data-row:hover {
  background-color: #f8fafc;
}

.sticky-col {
  position: sticky;
  left: 0;
  background: white;
  z-index: 5;
  min-width: 60px;
}

.time-col {
  font-family: 'Courier New', monospace;
  color: #6c757d;
}

.price-up {
  color: #28a745;
  font-weight: 500;
}

.price-down {
  color: #dc3545;
  font-weight: 500;
}

.high-col {
  color: #dc3545;
  font-weight: 500;
}

.low-col {
  color: #28a745;
  font-weight: 500;
}

.volume-col {
  font-family: 'Courier New', monospace;
  color: #6c757d;
}

.price-ratio-badge {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 20px;
  font-size: 0.75rem;
  font-weight: 600;
}

.ratio-high {
  background: rgba(220, 53, 69, 0.1);
  color: #dc3545;
}

.ratio-low {
  background: rgba(40, 167, 69, 0.1);
  color: #28a745;
}

.ratio-neutral {
  background: rgba(108, 117, 125, 0.1);
  color: #6c757d;
}

/* 10分钟对比指示 */
.change-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
}

.change-icon {
  font-size: 0.75rem;
}

.icon-up {
  color: #28a745;
}

.icon-down {
  color: #dc3545;
}

.icon-neutral {
  color: #6c757d;
}

.change-value {
  font-size: 0.8rem;
  font-weight: 500;
}

.text-green {
  color: #28a745;
}

.text-red {
  color: #dc3545;
}

/* 加载状态 */
.loading-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 255, 255, 0.9);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.loading-content {
  text-align: center;
  color: #6c757d;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 4px solid #e9ecef;
  border-top-color: #667eea;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 16px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 空状态 */
.empty-state {
  padding: 60px 20px;
  text-align: center;
  color: #6c757d;
}

.empty-icon {
  width: 80px;
  height: 80px;
  margin: 0 auto 20px;
  background: #f8f9fa;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 2.5rem;
  color: #adb5bd;
}

.empty-state h3 {
  font-size: 1.25rem;
  font-weight: 600;
  margin-bottom: 8px;
  color: #495057;
}

.empty-state p {
  margin-bottom: 20px;
}

/* 滚动条样式 */
.table-wrapper::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

.table-wrapper::-webkit-scrollbar-track {
  background: #f1f1f1;
}

.table-wrapper::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.table-wrapper::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

/* 对比按钮 */
.compare-btn {
  padding: 4px 8px;
  font-size: 0.75rem;
  border-radius: 4px;
}

/* 弹窗样式 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 800px;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e9ecef;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-radius: 12px 12px 0 0;
}

.modal-header h3 {
  margin: 0;
  font-size: 1.25rem;
}

.modal-close {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.3s;
}

.modal-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.modal-body {
  padding: 20px;
}

.compare-row {
  display: flex;
  align-items: stretch;
  gap: 20px;
  margin-bottom: 20px;
}

.compare-item {
  flex: 1;
  padding: 16px;
  border-radius: 8px;
  background: #f8f9fa;
}

.compare-item.current {
  border-left: 4px solid #20c997;
}

.compare-item.target {
  border-left: 4px solid #6f42c1;
}

.compare-label {
  font-weight: 600;
  color: #495057;
  margin-bottom: 8px;
}

.compare-time {
  font-size: 0.85rem;
  color: #6c757d;
  margin-bottom: 12px;
}

.compare-price {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px solid #e9ecef;
}

.compare-price .label {
  color: #6c757d;
  font-size: 0.85rem;
}

.compare-price .value {
  font-weight: 600;
  color: #212529;
}

.compare-price .value.high {
  color: #dc3545;
}

.compare-price .value.low {
  color: #28a745;
}

.compare-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.5rem;
  color: #6c757d;
}

.compare-result {
  text-align: center;
  padding: 16px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border-radius: 8px;
}

.result-label {
  font-size: 0.85rem;
  color: #6c757d;
  margin-bottom: 8px;
}

.result-value {
  font-size: 1.5rem;
  font-weight: bold;
}

/* 图表区域 */
.chart-section {
  margin-top: 30px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  overflow: hidden;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.chart-header h3 {
  margin: 0;
  font-size: 1.1rem;
}

.chart-tabs {
  display: flex;
  gap: 8px;
}

.chart-tab {
  padding: 6px 16px;
  border: none;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.2);
  color: white;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.3s;
  display: flex;
  align-items: center;
  gap: 6px;
}

.chart-tab:hover {
  background: rgba(255, 255, 255, 0.3);
}

.chart-tab.active {
  background: white;
  color: #667eea;
}

.chart-container {
  width: 100%;
  height: 400px;
  padding: 20px;
}
</style>