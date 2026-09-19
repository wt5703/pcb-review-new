<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import { ApiError } from '@/api/client'
import { reviewApi } from '@/api/review-api'
import type { TaskPage } from '@/api/types'

const form = reactive({ taskName: '', projectName: '', reviewType: '', status: '' })
const page = ref<TaskPage>({ total: 0, pageNo: 1, pageSize: 10, items: [] })
const loading = ref(false)
const error = ref('')
async function load(pageNo = 1): Promise<void> {
  loading.value = true; error.value = ''
  try { page.value = await reviewApi.listTasks({ ...form, pageNo, pageSize: page.value.pageSize }) } catch (cause) { error.value = cause instanceof ApiError ? cause.message : '加载任务失败' } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-head"><div><h1>任务列表</h1><p>展示当前用户有权限查看的全部评审任务。</p></div><RouterLink class="btn primary" to="/tasks/create">＋ 创建任务</RouterLink></div>
  <section class="card filters"><input v-model="form.taskName" placeholder="任务名称" @keyup.enter="load()" /><input v-model="form.projectName" placeholder="项目名称" @keyup.enter="load()" />
    <select v-model="form.reviewType"><option value="">全部评审类型</option><option value="PCB">PCB 评审</option><option value="SCHEMATIC">原理图评审</option></select>
    <select v-model="form.status"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="PCB_EXPERT_REVIEWING">专家评审</option><option value="MUTUAL_REVIEWING">互检中</option><option value="FINISHED">已结束</option></select><button class="btn primary" @click="load()">查询</button></section>
  <section class="card"><div v-if="error" class="api-error">{{ error }}<button class="btn" @click="load()">重试</button></div><div v-if="loading" class="loading">正在加载…</div>
    <div v-else class="table-wrap"><table class="data-table"><thead><tr><th>任务名称</th><th>项目名称</th><th>评审类型</th><th>设计名称</th><th>设计者 ID</th><th>当前状态</th><th>操作</th></tr></thead><tbody>
      <tr v-for="task in page.items" :key="task.id"><td><b>{{ task.taskName }}</b></td><td>{{ task.projectName }}</td><td>{{ task.reviewType === 'PCB' ? 'PCB 评审' : '原理图评审' }}</td><td>{{ task.designName }}</td><td>#{{ task.designerId }}</td><td><StatusTag :value="task.status" /></td><td><RouterLink class="btn compact" :to="`/tasks/${task.id}`">查看详情</RouterLink></td></tr><tr v-if="!page.items.length"><td colspan="7" class="empty">暂无符合条件的任务</td></tr></tbody></table></div>
    <footer class="pagination"><span>共 {{ page.total }} 条</span><button class="btn compact" :disabled="page.pageNo <= 1" @click="load(page.pageNo - 1)">上一页</button><b>{{ page.pageNo }}</b><button class="btn compact" :disabled="page.items.length < page.pageSize" @click="load(page.pageNo + 1)">下一页</button></footer>
  </section>
</template>
