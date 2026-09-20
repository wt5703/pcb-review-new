<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import { reviewApi } from '@/api/review-api'
import type { MyTask } from '@/api/types'

const items = ref<MyTask[]>([])
const loading = ref(false)
const error = ref('')
async function load(): Promise<void> { loading.value = true; error.value = ''; try { items.value = await reviewApi.getMyTasks() } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' } finally { loading.value = false } }
onMounted(load)
</script>

<template><div class="page-head"><div><h1>我的任务</h1><p>仅显示当前流程节点需要您结束、评审、分配、答复或确认的任务。</p></div></div><section class="card"><div v-if="error" class="api-error">{{ error }}<button class="btn" @click="load">重试</button></div><div v-else-if="loading" class="loading">正在加载…</div><div v-else class="table-wrap"><table class="data-table"><thead><tr><th>任务名称</th><th>项目名称</th><th>类型</th><th>状态</th><th>期望完成日期</th><th>设计者</th><th>评审角色</th><th>当前待办</th><th>操作</th></tr></thead><tbody><tr v-for="task in items" :key="task.id"><td><b>{{ task.taskName }}</b><small>{{ task.designName }}</small></td><td>{{ task.projectName }}</td><td>{{ task.reviewType === 'PCB' ? 'PCB布局布线评审' : '原理图评审' }}</td><td><StatusTag :value="task.status" /></td><td>{{ task.expectedCompletedDate }}</td><td>{{ task.designerName }}</td><td><span v-for="role in task.reviewRoles" :key="role" class="action-pill">{{ role }}</span></td><td><span v-for="action in task.actions" :key="action" class="action-pill">{{ action }}</span></td><td><RouterLink :to="`/tasks/${task.id}`" class="btn compact">立即处理</RouterLink></td></tr><tr v-if="!items.length"><td colspan="9" class="empty">暂无当前待办</td></tr></tbody></table></div></section></template>
