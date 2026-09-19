<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import { ApiError } from '@/api/client'
import { reviewApi } from '@/api/review-api'
import type { MyTask } from '@/api/types'

const loading = ref(true)
const error = ref('')
const tasks = ref<MyTask[]>([])
const summary = computed(() => ({
  all: tasks.value.length,
  review: tasks.value.filter((task) => task.actions.includes('REVIEW')).length,
  reply: tasks.value.filter((task) => task.actions.includes('REPLY_OPINION')).length,
  confirm: tasks.value.filter((task) => task.actions.includes('CONFIRM_OPINION')).length
}))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try { tasks.value = await reviewApi.getMyTasks() } catch (cause) { error.value = cause instanceof ApiError ? cause.message : '加载失败' } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-head"><div><h1>工作台</h1><p>聚合当前节点需要您处理的评审、答复、确认、分配与结束事项。</p></div><RouterLink class="btn primary" to="/tasks/create">＋ 创建评审任务</RouterLink></div>
  <div class="stat-grid">
    <div class="stat-card"><span>我的任务</span><b>{{ summary.all }}</b><small>当前流程待办</small></div>
    <div class="stat-card accent"><span>待我评审</span><b>{{ summary.review }}</b><small>当前节点为我处理</small></div>
    <div class="stat-card warning"><span>待我答复</span><b>{{ summary.reply }}</b><small>设计者意见答复</small></div>
    <div class="stat-card success"><span>待我确认</span><b>{{ summary.confirm }}</b><small>意见闭环确认</small></div>
  </div>
  <div class="two-column">
    <section class="card"><div class="section-head"><div><h2>待办任务</h2><p>严格按“我的任务”定义返回。</p></div><RouterLink class="text-link" to="/my-tasks">查看全部 →</RouterLink></div>
      <div v-if="loading" class="loading">正在从后端加载任务…</div>
      <div v-else-if="error" class="api-error"><b>加载失败</b><span>{{ error }}</span><button class="btn" @click="load">重试</button></div>
      <table v-else class="data-table"><thead><tr><th>任务</th><th>类型</th><th>状态</th><th>待办动作</th></tr></thead>
        <tbody><tr v-for="task in tasks.slice(0, 6)" :key="task.id"><td><RouterLink :to="`/tasks/${task.id}`" class="task-link">{{ task.taskName }}</RouterLink></td><td>{{ task.reviewType === 'PCB' ? 'PCB 评审' : '原理图评审' }}</td><td><StatusTag :value="task.status" /></td><td><span v-for="action in task.actions" :key="action" class="action-pill">{{ action }}</span></td></tr>
        <tr v-if="!tasks.length"><td colspan="4" class="empty">当前没有需要您处理的任务</td></tr></tbody></table>
    </section>
    <section class="card notification-card"><h2>流程说明</h2><p class="notice">多人同一评审角色时，所有人员提交后该角色才算完成；设计文件新版本仅对意见答复进行确认。</p>
      <ul><li>已结束任务不允许重新打开。</li><li>工艺、结构专家仅可见关联任务。</li><li>EMC 专家具备 PCB/原理图文件下载权限。</li></ul>
    </section>
  </div>
</template>
