<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import axios from 'axios'

// 数据状态
const ws = ref(null)
const wsConnected = ref(false)
const connecting = ref(false)
const showLogs = ref(false)
const logs = ref([])
const ethData = ref([])
const maxDataPoints = ref(1320)
const lastTimestamp = ref(0)

// 策略状态（由后端推送，前端只负责展示）
const tradeRecords = ref([])
const totalTrades = ref(0)
const winCount = ref(0)
const totalProfit = ref(0)

// API基础路径
const BASE_API = '/system/vallisusdt'

// 添加日志
const addLog = (type, message) => {
  const now = new Date()
  const time = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`
  logs.value.unshift({ time, type, message })
  if (logs.value.length > 100) {
    logs.value.pop()
  }
}

// 连接 WebSocket
const connectWebSocket = () => {
  if (wsConnected.value || connecting.value) return

  connecting.value = true
  addLog('info', '🔗 正在连接WebSocket...')

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.hostname || 'localhost'
  const url = `${protocol}//${host}:8080/system/vallisusdt/ws/kline`

  ws.value = new WebSocket(url)

  ws.value.onopen = () => {
    connecting.value = false
    wsConnected.value = true
    addLog('success', '✅ WebSocket连接成功')
    ws.value.send(JSON.stringify({ action: 'subscribe', interval: '1s' }))
  }

  ws.value.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)
      handleWsMessage(data)
    } catch (error) {
      addLog('error', `❌ 解析消息失败: ${error.message}`)
    }
  }

  ws.value.onerror = (error) => {
    connecting.value = false
    wsConnected.value = false
    addLog('error', `❌ WebSocket错误: ${error.message || '未知错误'}`)
  }

  ws.value.onclose = () => {
    connecting.value = false
    wsConnected.value = false
    addLog('warning', '⚠️ WebSocket连接已关闭')
  }
}

// 断开连接
const disconnectWebSocket = () => {
  if (ws.value) {
    ws.value.close()
    ws.value = null
  }
  wsConnected.value = false
  addLog('info', '🔌 WebSocket已断开')
}

// 安全转换为数字
const toNumber = (value) => {
  if (value === null || value === undefined) return 0
  return parseFloat(value) || 0
}

// 处理后端推送的交易事件
const handleTradeEvent = (trade) => {
  if (trade.type === 'open') {
    // 开单事件
    const record = {
      time: formatTime(trade.time),
      timestamp: trade.time,
      direction: trade.direction,
      openPrice: parseFloat(trade.openPrice),
      high20min: trade.high20min,
      low20min: trade.low20min,
      status: '待结算'
    }
    tradeRecords.value.unshift(record)
    if (tradeRecords.value.length > 50) tradeRecords.value.pop()
    totalTrades.value++
    addLog('info', `${trade.direction === '多单' ? '📈' : '📉'} 开${trade.direction} @${trade.openPrice}`)
  } else if (trade.type === 'settle') {
    // 结算事件：匹配对应开单记录
    const match = tradeRecords.value.find(t =>
      t.timestamp === trade.time && t.direction === trade.direction && t.status === '待结算'
    )
    if (match) {
      match.settlePrice = trade.settlePrice
      match.profit = trade.profit
      match.profitPercent = trade.profitPercent
      match.status = trade.status
      if (trade.status === '盈利') winCount.value++
      totalProfit.value += parseFloat(trade.profit)
      addLog('success', `💰 ${trade.direction}结算: ${trade.status} ${trade.profit >= 0 ? '+' : ''}${trade.profit}`)
    }
  }
}

// 计算最近10分钟的最高值、最低值和当前价格在范围内的比例
const calculate10MinStats = (data, currentIndex) => {
  const minutes10Ago = 600 // 10分钟 = 600秒
  const startIndex = Math.max(0, currentIndex - minutes10Ago)
  
  let high10min = data[startIndex]?.high || 0
  let low10min = data[startIndex]?.low || Infinity
  
  for (let i = startIndex; i <= currentIndex && i < data.length; i++) {
    const item = data[i]
    if (item.high > high10min) high10min = item.high
    if (item.low < low10min) low10min = item.low
  }
  
  // 处理边界情况
  if (low10min === Infinity) low10min = high10min
  if (high10min === 0) high10min = low10min
  
  const currentPrice = data[currentIndex]?.close || 0
  const range = high10min - low10min
  const ratio = range > 0 ? ((currentPrice - low10min) / range * 100).toFixed(2) : '50.00'
  
  return {
    high10min: high10min.toFixed(4),
    low10min: low10min.toFixed(4),
    priceRatio: ratio
  }
}

// 更新所有数据的10分钟统计
const updateAll10MinStats = () => {
  for (let i = 0; i < ethData.value.length; i++) {
    const stats = calculate10MinStats(ethData.value, i)
    ethData.value[i].high10min = stats.high10min
    ethData.value[i].low10min = stats.low10min
    ethData.value[i].priceRatio = stats.priceRatio
  }
}

// 处理消息
const handleWsMessage = (data) => {
  // 交易事件（后端策略引擎推送）
  if (data && data.trade) {
    handleTradeEvent(data.trade)
    return
  }

  if (!data || !data.data) return

  addLog('info', `📥 收到数据: ${data.data.length}条`)

  // 解析数据
  const newData = data.data.map((item) => {
    const open = toNumber(item.open || item[1])
    const close = toNumber(item.close || item[2])
    const high = toNumber(item.high || item[3])
    const low = toNumber(item.low || item[4])
    const volume = toNumber(item.volume || item[5])
    const timeVal = toNumber(item.startTime || item.timestamp)
    const time = timeVal > 0 ? timeVal : Date.now()
    
    return {
      time: time,
      timeStr: formatTime(time),
      open: open,
      close: close,
      high: high,
      low: low,
      volume: volume,
      change: (close - open).toFixed(2),
      changePercent: (open !== 0 ? ((close - open) / open * 100).toFixed(2) : '0.00'),
      high10min: '0.0000',
      low10min: '0.0000',
      priceRatio: '0.00'
    }
  })

  // 添加新数据，确保时间连续，每秒一行
    if (newData.length > 0) {
      // 获取最新的一条数据
      const latestData = newData[newData.length - 1]
      const currentSecond = Math.floor(latestData.time / 1000)
      
      // 如果是第一次接收数据
      if (lastTimestamp.value === 0) {
        ethData.value = [latestData]
        updateAll10MinStats()
        lastTimestamp.value = latestData.time
        return
      }
      
      // 检查是否已有相同秒数的数据
      const existingIndex = ethData.value.findIndex(item => {
        return Math.floor(item.time / 1000) === currentSecond
      })
      
      if (existingIndex >= 0) {
        // 更新已存在的同秒数据
        ethData.value[existingIndex] = latestData
        updateAll10MinStats()
      } else {
        // 填充缺失的时间点
        const lastSecond = Math.floor(lastTimestamp.value / 1000)
        
        // 从上次时间的下一秒开始，到当前秒的前一秒，填充缺失数据
        for (let s = lastSecond + 1; s < currentSecond; s++) {
          const fillTime = s * 1000
          // 使用最后已知的数据作为填充（保持价格不变）
          const lastKnownData = ethData.value[ethData.value.length - 1] || latestData
          ethData.value.push({
            time: fillTime,
            timeStr: formatTime(fillTime),
            open: lastKnownData.close,
            close: lastKnownData.close,
            high: lastKnownData.close,
            low: lastKnownData.close,
            volume: 0,
            change: '0.00',
            changePercent: '0.00',
            high10min: '0.0000',
            low10min: '0.0000',
            priceRatio: '0.00'
          })
        }
        
        // 添加新数据
        ethData.value.push(latestData)
        
        // 保持数据点数量限制
        while (ethData.value.length > maxDataPoints.value) {
          ethData.value.shift()
        }
        
        // 更新所有数据的10分钟统计
        updateAll10MinStats()
      }
      
      lastTimestamp.value = latestData.time
    }
  }

// 格式化时间
const formatTime = (timestamp) => {
  const d = new Date(timestamp)
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`
}

// 切换日志显示
const toggleLogs = () => {
  showLogs.value = !showLogs.value
}

// 清空日志
const clearLogs = () => {
  logs.value = []
}

// 生命周期
onMounted(() => {
  connectWebSocket()
})

onUnmounted(() => {
  disconnectWebSocket()
})
</script>

<template>
  <div class="eth-detail-container">
    <!-- 头部 -->
    <div class="header">
      <h1>📊 ETH/USDT 实时数据</h1>
      <div class="controls">
        <button
            @click="wsConnected ? disconnectWebSocket() : connectWebSocket()"
            :class="{ 'connected': wsConnected, 'disconnected': !wsConnected }"
            :disabled="connecting"
        >
          {{ connecting ? '连接中...' : (wsConnected ? '🔌 断开连接' : '🔗 连接WebSocket') }}
        </button>
        <span class="status" :class="wsConnected ? 'online' : 'offline'">
          {{ wsConnected ? '✓ 已连接' : '✗ 未连接' }}
        </span>
        <button @click="toggleLogs" class="log-toggle">
          {{ showLogs ? '▼ 隐藏日志' : '▲ 显示日志' }}
        </button>
      </div>
    </div>

    <!-- 日志面板 -->
    <div class="log-panel" v-show="showLogs">
      <div class="log-header">
        <span>📝 连接日志</span>
        <button @click="clearLogs">清空</button>
      </div>
      <div class="log-content">
        <div v-for="(log, index) in logs" :key="index" :class="log.type">
          <span class="log-time">{{ log.time }}</span>
          <span>{{ log.message }}</span>
        </div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="table-container">
      <div class="table-header">
        <span>实时数据 (最近 {{ maxDataPoints }} 条)</span>
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>开盘价</th>
            <th>收盘价</th>
            <th>最高价</th>
            <th>最低价</th>
            <th>成交量</th>
            <th>涨跌额</th>
            <th>涨跌幅</th>
            <th>10分钟最高</th>
            <th>10分钟最低</th>
            <th>价格比例</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(item, index) in ethData" :key="index" :class="{ 'up': parseFloat(item.change) >= 0, 'down': parseFloat(item.change) < 0 }">
            <td>{{ item.timeStr }}</td>
            <td>${{ item.open.toFixed(4) }}</td>
            <td>${{ item.close.toFixed(4) }}</td>
            <td>${{ item.high.toFixed(4) }}</td>
            <td>${{ item.low.toFixed(4) }}</td>
            <td>{{ item.volume.toLocaleString() }}</td>
            <td :class="parseFloat(item.change) >= 0 ? 'up' : 'down'">
              {{ parseFloat(item.change) >= 0 ? '+' : '' }}{{ item.change }}
            </td>
            <td :class="parseFloat(item.changePercent) >= 0 ? 'up' : 'down'">
              {{ parseFloat(item.changePercent) >= 0 ? '+' : '' }}{{ item.changePercent }}%
            </td>
            <td class="highlight">${{ item.high10min }}</td>
            <td class="highlight">${{ item.low10min }}</td>
            <td class="ratio">
              <div class="ratio-bar">
                <div class="ratio-fill" :style="{ width: item.priceRatio + '%' }"></div>
                <span class="ratio-value">{{ item.priceRatio }}%</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="ethData.length === 0" class="empty-state">
        <span>等待数据中...</span>
      </div>
    </div>

    <!-- 策略面板（后端推送，前端展示） -->
    <div class="strategy-panel">
      <div class="strategy-header">
        <span>🎯 实时策略监控</span>
        <div class="strategy-stats">
          <span>总交易: {{ totalTrades }}</span>
          <span>胜率: {{ totalTrades > 0 ? ((winCount / totalTrades) * 100).toFixed(2) : '0.00' }}%</span>
          <span :class="totalProfit >= 0 ? 'profit' : 'loss'">
            总收益: {{ totalProfit >= 0 ? '+' : '' }}${{ totalProfit.toFixed(2) }}
          </span>
        </div>
      </div>
      <div class="trade-list">
        <div v-for="(trade, index) in tradeRecords" :key="index" class="trade-item" :class="trade.status === '盈利' ? 'win' : trade.status === '亏损' ? 'lose' : ''">
          <span class="trade-time">{{ trade.time }}</span>
          <span :class="trade.direction === '多单' ? 'long' : 'short'">[{{ trade.direction }}]</span>
          <span>开仓: ${{ trade.openPrice.toFixed ? trade.openPrice.toFixed(4) : trade.openPrice }}</span>
          <span>高: ${{ trade.high20min }}</span>
          <span>低: ${{ trade.low20min }}</span>
          <span v-if="trade.settlePrice">平仓: ${{ trade.settlePrice }}</span>
          <span v-if="trade.profit" :class="parseFloat(trade.profit) >= 0 ? 'profit' : 'loss'">
            收益: {{ parseFloat(trade.profit) >= 0 ? '+' : '' }}${{ trade.profit }} ({{ trade.profitPercent }}%)
          </span>
          <span class="trade-status">{{ trade.status }}</span>
        </div>
        <div v-if="tradeRecords.length === 0" class="empty-trades">
          暂无交易记录
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.eth-detail-container {
  width: 100%;
  min-height: 100vh;
  background: #0d0d1a;
  color: #fff;
  padding: 20px;
  box-sizing: border-box;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 15px;
  border-bottom: 1px solid #333;

  h1 {
    font-size: 24px;
    color: #fff;
  }

  .controls {
    display: flex;
    gap: 15px;
    align-items: center;
  }

  button {
    padding: 10px 20px;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    font-size: 14px;
    transition: all 0.3s;
  }

  button.connected {
    background: #FF4757;
    color: white;
  }

  button.disconnected {
    background: #2196F3;
    color: white;
  }

  button.log-toggle {
    background: #2a2a4a;
    color: #999;
    padding: 6px 12px;
    font-size: 12px;
  }

  button.strategy-btn {
    background: #4CAF50;
    color: white;
    padding: 8px 16px;
    font-size: 12px;
  }

  button.strategy-btn.strategy-disabled {
    background: #f44336;
  }

  button:hover:not(:disabled) {
    opacity: 0.9;
    transform: translateY(-1px);
  }

  button:disabled {
    background: #333;
    cursor: not-allowed;
  }

  .status {
    font-size: 12px;
    padding: 4px 12px;
    border-radius: 4px;
  }

  .status.online {
    background: rgba(0, 212, 170, 0.2);
    color: #00D4AA;
  }

  .status.offline {
    background: rgba(255, 71, 87, 0.2);
    color: #FF4757;
  }
}

.log-panel {
  background: #1a1a2e;
  border-radius: 8px;
  margin-bottom: 20px;
  overflow: hidden;

  .log-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 15px;
    background: #2a2a4a;
    font-size: 12px;

    button {
      background: none;
      border: none;
      color: #666;
      cursor: pointer;
      font-size: 12px;
    }

    button:hover {
      color: #fff;
    }
  }

  .log-content {
    max-height: 150px;
    overflow-y: auto;
    padding: 10px 15px;
    font-size: 12px;
  }

  .log-time {
    color: #666;
    margin-right: 8px;
  }

  .info {
    color: #2196F3;
  }

  .success {
    color: #00D4AA;
  }

  .error {
    color: #FF4757;
  }

  .warning {
    color: #FF9800;
  }
}

.table-container {
  background: #1a1a2e;
  border-radius: 8px;
  overflow: hidden;

  .table-header {
    padding: 15px 20px;
    background: #2a2a4a;
    font-size: 14px;
    font-weight: 500;
    border-bottom: 1px solid #333;
  }

  .data-table {
    width: 100%;
    border-collapse: collapse;
  }

  th, td {
    padding: 12px 15px;
    text-align: right;
    font-size: 13px;
    border-bottom: 1px solid #2a2a4a;
  }

  th {
    background: #2a2a4a;
    color: #999;
    font-weight: 500;
    text-align: center;
  }

  td {
    color: #ccc;
  }

  tr:hover {
    background: rgba(33, 150, 243, 0.1);
  }

  tr.up td:last-child,
  tr.up td:nth-last-child(2) {
    color: #00D4AA;
  }

  tr.down td:last-child,
  tr.down td:nth-last-child(2) {
    color: #FF4757;
  }

  .highlight {
    color: #FFD700;
    font-weight: 500;
  }

  .ratio {
    padding: 0;
  }

  .ratio-bar {
    position: relative;
    height: 24px;
    background: rgba(33, 150, 243, 0.2);
    border-radius: 4px;
    overflow: hidden;
  }

  .ratio-fill {
    position: absolute;
    left: 0;
    top: 0;
    height: 100%;
    background: linear-gradient(90deg, #00D4AA, #2196F3);
    transition: width 0.3s ease;
  }

  .ratio-value {
    position: absolute;
    right: 5px;
    top: 50%;
    transform: translateY(-50%);
    font-size: 11px;
    color: #fff;
    font-weight: 500;
    text-shadow: 0 1px 2px rgba(0,0,0,0.5);
  }

  .empty-state {
    padding: 50px;
    text-align: center;
    color: #666;
  }
}

.strategy-panel {
  background: #1a1a2e;
  border-radius: 8px;
  margin-top: 20px;
  overflow: hidden;

  .strategy-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 15px 20px;
    background: linear-gradient(90deg, #4CAF50, #2196F3);
    font-size: 14px;
    font-weight: 500;

    .strategy-stats {
      display: flex;
      gap: 20px;
      font-size: 12px;

      .profit {
        color: #00D4AA;
      }

      .loss {
        color: #FF4757;
      }
    }
  }

  .trade-list {
    max-height: 300px;
    overflow-y: auto;
    padding: 10px;
  }

  .trade-item {
    display: flex;
    gap: 15px;
    padding: 10px 15px;
    border-bottom: 1px solid #2a2a4a;
    font-size: 12px;
    color: #ccc;

    &.win {
      background: rgba(0, 212, 170, 0.1);
    }

    &.lose {
      background: rgba(255, 71, 87, 0.1);
    }

    .trade-time {
      color: #666;
      min-width: 70px;
    }

    .long {
      color: #00D4AA;
      font-weight: 500;
    }

    .short {
      color: #FF4757;
      font-weight: 500;
    }

    .profit {
      color: #00D4AA;
    }

    .loss {
      color: #FF4757;
    }

    .trade-status {
      margin-left: auto;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      background: #2a2a4a;
      color: #999;

      &.盈利 {
        background: rgba(0, 212, 170, 0.2);
        color: #00D4AA;
      }

      &.亏损 {
        background: rgba(255, 71, 87, 0.2);
        color: #FF4757;
      }
    }
  }

  .empty-trades {
    padding: 30px;
    text-align: center;
    color: #666;
    font-size: 13px;
  }
}
</style>