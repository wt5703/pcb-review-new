<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { reviewApi } from '@/api/review-api'

type ReviewerWhitelistRole = 'HARDWARE_EXPERT' | 'EMC_EXPERT' | 'STRUCTURE_EXPERT' | 'PROCESS_EXPERT' | 'PCB_EXPERT' | 'PCB_MUTUAL_CHECK' | 'SCHEMATIC_MUTUAL_CHECK'
type WhitelistPerson = { id: number; employeeNo: string; displayName: string; email: string; mobile?: string; departmentName: string; roles: string[] }
type WhitelistItem = {
  id: number
  reviewRole: ReviewerWhitelistRole
  employeeNo: string
  displayName?: string
  email?: string
  mobile?: string
  departmentName?: string
  createdAt?: string
}

const roleDefinitions: Array<{ code: ReviewerWhitelistRole; name: string; description: string }> = [
  { code: 'HARDWARE_EXPERT', name: '硬件评审', description: '硬件设计与原理图相关评审' },
  { code: 'EMC_EXPERT', name: 'EMC评审', description: 'EMC 设计要求相关评审' },
  { code: 'STRUCTURE_EXPERT', name: '结构评审', description: '结构、装配与空间相关评审' },
  { code: 'PROCESS_EXPERT', name: '工艺评审', description: '制造工艺与可制造性相关评审' },
  { code: 'PCB_EXPERT', name: 'PCB评审', description: 'PCB 布局布线评审' },
  { code: 'PCB_MUTUAL_CHECK', name: 'PCB互检单评审', description: 'PCB 互检单分配候选人员' },
  { code: 'SCHEMATIC_MUTUAL_CHECK', name: '原理图互检单评审', description: '原理图互检单分配候选人员' }
]

const users = ref<WhitelistPerson[]>([])
const mappings = ref<WhitelistItem[]>([])
const selectedEmployeeNos = reactive<Record<ReviewerWhitelistRole, string[]>>({
  HARDWARE_EXPERT: [], EMC_EXPERT: [], STRUCTURE_EXPERT: [], PROCESS_EXPERT: [], PCB_EXPERT: [], PCB_MUTUAL_CHECK: [], SCHEMATIC_MUTUAL_CHECK: []
})
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = ref('')
const keyword = ref('')
const pageNo = ref(1)
const pageSize = 20
const total = ref(0)

function userRoleNames(user: WhitelistPerson): string { return user.roles.join(' / ') || '未配置评审角色' }
function roleName(role: ReviewerWhitelistRole): string { return roleDefinitions.find((item) => item.code === role)?.name || role }
function formatDateTime(value?: string): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

async function load(resetPage = false): Promise<void> {
  if (resetPage) pageNo.value = 1
  loading.value = true
  error.value = ''
  try {
    const [mappingPage, candidatePage] = await Promise.all([
      reviewApi.listReviewerWhitelists({ keyword: keyword.value || undefined, pageNo: pageNo.value, pageSize }),
      reviewApi.listReviewerWhitelists({ pageNo: 1, pageSize: 1000 })
    ])
    const candidateMappings = candidatePage.items
    users.value = [...new Map(candidateMappings.map((item) => [item.employeeNo, { id: item.id, employeeNo: item.employeeNo, displayName: item.displayName || item.employeeNo, email: item.email || '', mobile: item.mobile, departmentName: item.departmentName || '', roles: candidateMappings.filter((mapping) => mapping.employeeNo === item.employeeNo).map((mapping) => mapping.reviewRole) }])).values()]
    mappings.value = mappingPage.items
    total.value = mappingPage.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载白名单数据失败'
  } finally {
    loading.value = false
  }
}

async function addWhitelist(): Promise<void> {
  const roleEmployeeNos = roleDefinitions
    .map(({ code }) => ({ reviewRole: code, employeeNos: [...new Set(selectedEmployeeNos[code])] }))
    .filter((item) => item.employeeNos.length)
  if (!roleEmployeeNos.length) { error.value = '请至少为一种评审角色勾选人员。'; return }
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await reviewApi.addReviewerWhitelist(roleEmployeeNos)
    roleDefinitions.forEach(({ code }) => { selectedEmployeeNos[code] = [] })
    message.value = result.createdCount ? `已新增 ${result.createdCount} 条白名单映射。` : '所选人员已在对应白名单中。'
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '新增白名单失败'
  } finally {
    saving.value = false
  }
}

async function removeWhitelist(item: WhitelistItem): Promise<void> {
  if (!window.confirm(`确认移除 ${item.displayName || item.employeeNo} 的此角色白名单吗？`)) return
  error.value = ''
  message.value = ''
  try {
    await reviewApi.removeReviewerWhitelist({ id: item.id })
    message.value = '白名单已移除。'
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '移除白名单失败'
  }
}

onMounted(load)
</script>

<template>
  <div class="whitelist-page">
    <header class="page-heading"><div><h1>评审白名单管理</h1><p>从初始化人员中为评审与互检职责配置可分配人员。</p></div></header>
    <p v-if="message" class="toast-message">{{ message }}</p>
    <p v-if="error" class="form-error">{{ error }}</p>

    <section class="card add-card">
      <div class="section-head"><div><h2>新增评审白名单</h2><p>可为同一人员勾选多个评审角色；保存后按“角色 + 员工工号”建立映射。</p></div></div>
      <div class="role-selection-grid">
        <article v-for="role in roleDefinitions" :key="role.code" class="role-selection-card">
          <header><b>{{ role.name }}</b><small>{{ role.description }}</small></header>
          <div class="candidate-list">
            <label v-for="user in users" :key="user.id" class="candidate-option">
              <input v-model="selectedEmployeeNos[role.code]" type="checkbox" :value="user.employeeNo" />
              <span><b>{{ user.displayName }}</b><small>{{ user.employeeNo }} · {{ user.departmentName }}</small><em>{{ userRoleNames(user) }}</em></span>
            </label>
            <p v-if="!users.length && !loading" class="empty">暂无初始化人员。</p>
          </div>
        </article>
      </div>
      <footer class="form-footer"><button class="btn primary" :disabled="saving || loading" @click="addWhitelist">{{ saving ? '保存中…' : '保存白名单' }}</button></footer>
    </section>

    <section class="card current-card">
      <div class="section-head"><div><h2>当前白名单</h2><p>展示当前启用人员的联系方式、评审角色与创建时间，可精确删除单条角色人员关系。</p></div><span class="tag">{{ total }} 条</span></div>
      <form class="whitelist-search" @submit.prevent="load(true)"><input v-model.trim="keyword" placeholder="按工号或姓名查询" /><button class="btn" type="submit">查询</button><button class="btn compact" type="button" @click="keyword = ''; load(true)">重置</button></form>
      <div v-if="loading" class="loading">正在加载白名单…</div>
      <div v-else class="table-wrap">
        <table class="data-table whitelist-table">
          <thead><tr><th>姓名</th><th>工号</th><th>邮箱</th><th>手机号</th><th>角色</th><th>创建时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in mappings" :key="item.id">
              <td>{{ item.displayName || '—' }}</td>
              <td>{{ item.employeeNo }}</td>
              <td>{{ item.email || '—' }}</td>
              <td>{{ item.mobile || '—' }}</td>
              <td><span class="role-badge">{{ roleName(item.reviewRole) }}</span></td>
              <td>{{ formatDateTime(item.createdAt) }}</td>
              <td><button class="btn compact danger" @click="removeWhitelist(item)">删除</button></td>
            </tr>
            <tr v-if="!mappings.length"><td colspan="7" class="empty">暂未配置白名单人员</td></tr>
          </tbody>
        </table>
      </div>
      <footer class="pagination"><span>第 {{ pageNo }} 页，共 {{ total }} 条</span><div><button class="btn compact" :disabled="loading || pageNo <= 1" @click="pageNo--; load()">上一页</button><button class="btn compact" :disabled="loading || pageNo * pageSize >= total" @click="pageNo++; load()">下一页</button></div></footer>
    </section>
  </div>
</template>

<style scoped>
.whitelist-page{max-width:1400px;margin:0 auto}.page-heading{margin:8px 0 20px}.page-heading h1{margin:0;color:#1d2740;font-size:24px}.page-heading p{margin:7px 0 0;color:#737c91}.add-card,.current-card{padding:20px;margin-top:16px}.section-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:16px}.section-head h2{margin:0;color:#26314a;font-size:17px}.section-head p{margin:6px 0 0;color:#737c91;font-size:13px}.role-selection-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.role-selection-card{overflow:hidden;border:1px solid #dfe3ed;border-radius:10px;background:#fff}.role-selection-card>header{display:flex;align-items:center;gap:9px;min-height:52px;padding:0 14px;border-bottom:1px solid #e9ebf2;background:#fbfcff}.role-selection-card>header b{color:#27324c;font-size:15px}.role-selection-card>header small{color:#737c91;font-size:12px}.candidate-list{max-height:270px;overflow:auto}.candidate-option{display:flex;gap:10px;align-items:flex-start;padding:10px 14px;border-bottom:1px solid #f0f1f5;cursor:pointer}.candidate-option:last-child{border-bottom:0}.candidate-option input{margin-top:4px}.candidate-option span{display:grid;gap:3px}.candidate-option b{color:#2b344e;font-size:13px}.candidate-option small{color:#7b8295;font-size:12px}.candidate-option em{color:#786be2;font-size:11px;font-style:normal}.form-footer{display:flex;justify-content:flex-end;margin-top:16px;padding-top:15px;border-top:1px solid #e8eaf0}.role-badge{padding:4px 8px;border-radius:6px;background:#f0edff;color:#6657d9;font-size:11px;white-space:nowrap}.whitelist-search{display:flex;gap:8px;margin:-2px 0 14px}.whitelist-search input{width:260px}.table-wrap{overflow-x:auto}.whitelist-table{min-width:900px}.whitelist-table th,.whitelist-table td{white-space:nowrap}.pagination{display:flex;align-items:center;justify-content:space-between;margin-top:14px;color:#737c91;font-size:13px}.pagination div{display:flex;gap:8px}.empty{margin:0;padding:16px;color:#8990a2;font-size:13px;text-align:center}.loading{padding:26px;color:#737c91;text-align:center}@media(max-width:820px){.role-selection-grid{grid-template-columns:1fr}.add-card,.current-card{padding:14px}.section-head{flex-direction:column}}@media(max-width:600px){.whitelist-search{flex-wrap:wrap}.whitelist-search input{width:100%}}
</style>
