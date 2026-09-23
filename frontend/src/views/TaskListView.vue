<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import { ApiError } from '@/api/client'
import { reviewApi } from '@/api/review-api'
import type { TaskPage } from '@/api/types'

const form = reactive({ taskName: '', projectName: '', designerName: '', reviewType: '', status: '' })
const page = ref<TaskPage>({ total: 0, pageNo: 1, pageSize: 10, items: [] })
const reviewRoleNames = ref<Record<string, string>>({
  HARDWARE_EXPERT: '硬件评审', EMC_EXPERT: 'EMC评审', PCB_EXPERT: 'PCB评审', PROCESS_EXPERT: '工艺评审', STRUCTURE_EXPERT: '结构评审',
  PCB_MUTUAL_CHECK: 'PCB互检', SCHEMATIC_HARDWARE_EXPERT: '原理图硬件评审', SCHEMATIC_OTHER_EXPERT: '原理图其他评审', SCHEMATIC_MUTUAL_CHECK: '原理图互检', SCHEMATIC_LEADER: '原理图组长'
})
const loading = ref(false)
const error = ref('')
async function load(pageNo = 1): Promise<void> {
  loading.value = true; error.value = ''
  try {
    page.value = await reviewApi.listTasks({ ...form, pageNo, pageSize: page.value.pageSize })
    try {
      const options = await reviewApi.getTaskOptions()
      reviewRoleNames.value = { ...reviewRoleNames.value, ...Object.fromEntries(options.reviewRoles.map((role) => [role.code, role.name])) }
    } catch { /* 字典不可用时继续显示内置中文名称，不影响任务列表查询。 */ }
  } catch (cause) { error.value = cause instanceof ApiError ? cause.message : '加载任务失败' } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-head"><div><h1>任务列表</h1><p>展示当前用户有权限查看的全部评审任务。</p></div><RouterLink class="btn primary" to="/tasks/create">＋ 创建任务</RouterLink></div>
  <section class="card filters task-filters"><div class="keyword-pair"><input v-model="form.taskName" placeholder="任务名称" @keyup.enter="load()" /><input v-model="form.projectName" placeholder="项目名称" @keyup.enter="load()" /></div>
    <select v-model="form.reviewType"><option value="">评审类型</option><option value="PCB">PCB布局布线评审</option><option value="SCHEMATIC">原理图评审</option></select>
    <select v-model="form.status"><option value="">当前状态</option><option value="DRAFT">草稿</option><option value="PCB_EXPERT_REVIEWING">专家评审</option><option value="PCB_PROCESS_STRUCTURE_REVIEWING">工艺/结构评审</option><option value="MUTUAL_CHECK_PENDING_ASSIGNMENT">互检单待分配</option><option value="MUTUAL_CHECK_REVIEWING">互检单评审</option><option value="SCHEMATIC_PENDING_HARDWARE_EXPERT_ASSIGNMENT">待分配硬件专家</option><option value="SCHEMATIC_REVIEWING">原理图评审</option><option value="FINISHED">结束</option></select><input v-model="form.designerName" placeholder="设计者（模糊查询）" @keyup.enter="load()" /><button class="btn primary" @click="load()">查询</button></section>
  <section class="card"><div v-if="error" class="api-error">{{ error }}<button class="btn" @click="load()">重试</button></div><div v-if="loading" class="loading">正在加载…</div>
    <div v-else class="table-wrap"><table class="data-table"><thead><tr><th>任务名称</th><th>项目名称</th><th>类型</th><th>状态</th><th>期望完成日期</th><th>设计者</th><th>评审角色</th><th>操作</th></tr></thead><tbody>
      <tr v-for="task in page.items" :key="task.id"><td><b>{{ task.taskName }}</b></td><td>{{ task.projectName }}</td><td>{{ task.reviewType === 'PCB' ? 'PCB布局布线评审' : '原理图评审' }}</td><td><StatusTag :value="task.status" /></td><td>{{ task.expectedCompletedDate }}</td><td>{{ task.designerName }}</td><td><span v-for="role in task.reviewRoles" :key="role" class="action-pill">{{ reviewRoleNames[role] ?? '未配置角色' }}</span></td><td><RouterLink class="btn compact" :to="`/tasks/${task.id}`">查看详情</RouterLink></td></tr><tr v-if="!page.items.length"><td colspan="8" class="empty">暂无符合条件的任务</td></tr></tbody></table></div>
    <footer class="pagination"><span>共 {{ page.total }} 条</span><button class="btn compact" :disabled="page.pageNo <= 1" @click="load(page.pageNo - 1)">上一页</button><b>{{ page.pageNo }}</b><button class="btn compact" :disabled="page.items.length < page.pageSize" @click="load(page.pageNo + 1)">下一页</button></footer>
  </section>
</template>

<style scoped>
.task-filters{grid-template-columns:2fr 1fr 1fr 1fr auto}.keyword-pair{display:grid;grid-template-columns:1fr 1fr;gap:8px}@media(max-width:820px){.task-filters,.keyword-pair{grid-template-columns:1fr}}
</style>
