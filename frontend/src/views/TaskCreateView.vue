<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { reviewApi } from '@/api/review-api'
import type { TaskFileReference, TaskFile } from '@/api/types'
import { identity } from '@/stores/identity'

const router = useRouter()
const route = useRoute()
const editTaskId = computed(() => Number(route.params.taskId) || 0)
const editing = computed(() => editTaskId.value > 0)
const isAdministrator = computed(() => identity.roles.split(',').map((role) => role.trim()).includes('HARDWARE_DEPARTMENT_MANAGER'))
const form = reactive({ reviewType: 'PCB', taskName: '', projectName: '', designName: '', designerId: identity.userId, designerName: `设计者#${identity.userId}`, expectedCompletedDate: '', pcbType: '', expertLeaderId: '', expertLeaderName: '', reviewRoles: [] as string[], reviewDescription: '' })
const sourceFiles = ref<File[]>([])
const uploadedFiles = ref<TaskFileReference[]>([])
const savedFiles = ref<TaskFile[]>([])
const pcbTypes = ref<string[]>(['BMU板', 'BSU板', '分流器板', '高压板', '转接板', '储能板', '其他'])
const reviewRoles = ref<Array<{ code: string; name: string }>>([])
const mockUsers = ref<Array<{ id: number; displayName: string; roles: string[] }>>([])
const saving = ref(false)
const error = ref('')

watch(() => form.reviewType, (type) => { if (type === 'SCHEMATIC') form.pcbType = '' })
onMounted(async () => { try { const [options, users] = await Promise.all([reviewApi.getTaskOptions(), reviewApi.listMockUsers()]); pcbTypes.value = options.pcbTypes; reviewRoles.value = options.reviewRoles; mockUsers.value = users; const currentUser = users.find((user) => user.id === identity.userId); if (currentUser) form.designerName = currentUser.displayName
  if (editing.value) { const task = await reviewApi.getTask(editTaskId.value); if (task.status !== 'DRAFT' || (task.designerId !== identity.userId && !isAdministrator.value)) { throw new Error('只有任务设计者或平台管理员可以编辑尚未提交的任务。') }; Object.assign(form, { reviewType: task.reviewType, taskName: task.taskName, projectName: task.projectName, designName: task.designName, designerId: task.designerId, designerName: task.designerName, expectedCompletedDate: task.expectedCompletedDate, pcbType: task.pcbType ?? '', expertLeaderId: task.expertLeaderId, expertLeaderName: task.expertLeaderName, reviewRoles: task.reviewRoles, reviewDescription: task.reviewDescription ?? '' }); savedFiles.value = await reviewApi.latestFiles(editTaskId.value, task.reviewType === 'PCB' ? 'PCB_REVIEW' : 'SCHEMATIC_REVIEW') }
} catch (cause) { error.value = cause instanceof Error ? cause.message : '字典、用户目录或任务加载失败。' } })
function selectDesigner(): void { const user = mockUsers.value.find((item) => item.id === Number(form.designerId)); if (user) form.designerName = user.displayName }
function selectLeader(): void { const user = mockUsers.value.find((item) => item.id === Number(form.expertLeaderId)); if (user) form.expertLeaderName = user.displayName }
function sourceFileKey(file: File): string { return `${file.name}:${file.size}:${file.lastModified}` }
function selectFiles(event: Event): void {
  const input = event.target as HTMLInputElement
  const selected = Array.from(input.files ?? [])
  const existing = new Set(sourceFiles.value.map(sourceFileKey))
  sourceFiles.value.push(...selected.filter((file) => !existing.has(sourceFileKey(file))))
  input.value = ''
}
function removeSelectedFile(file: File): void {
  const key = sourceFileKey(file)
  sourceFiles.value = sourceFiles.value.filter((item) => sourceFileKey(item) !== key)
}
async function save(submitNow: boolean): Promise<void> {
  error.value = ''; saving.value = true
  try {
    if (sourceFiles.value.length) {
      const newFileIds = await reviewApi.uploadInitialFilesToCompany(sourceFiles.value, form.reviewType as 'PCB' | 'SCHEMATIC')
      uploadedFiles.value.push(...newFileIds)
      sourceFiles.value = []
    }
    const body = { reviewType: form.reviewType as 'PCB' | 'SCHEMATIC', taskName: form.taskName, projectName: form.projectName, designerId: Number(form.designerId), designerName: form.designerName, designName: form.designName, pcbType: form.pcbType || undefined, expectedCompletedDate: form.expectedCompletedDate, expertLeaderId: Number(form.expertLeaderId), expertLeaderName: form.expertLeaderName, reviewRoles: form.reviewRoles, reviewDescription: form.reviewDescription || undefined, files: uploadedFiles.value }
    const task = submitNow
      ? await reviewApi.submitTask(body, editing.value ? editTaskId.value : undefined)
      : await reviewApi.saveTask(body, editing.value ? editTaskId.value : undefined)
    await router.push(`/tasks/${task.id}`)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败，请检查服务端返回的字段错误。' } finally { saving.value = false }
}
</script>

<template>
  <div class="page-head"><div><h1>{{ editing ? '编辑评审任务' : '创建评审任务' }}</h1><p>{{ editing ? '仅可编辑尚未提交的任务草稿，可继续追加文件后保存或直接提交。' : '保存草稿或提交评审；必填规则由后端统一校验并返回准确提示。' }}</p></div></div>
  <form class="card form-card task-create-card" @submit.prevent="save(true)"><div class="notice">文件先上传到公司资源服务，提交任务时仅携带上传接口返回的多个文件 UUID；PCB 系统根据 UUID 读取并登记文件元数据。</div>
    <div class="form-grid create-grid">
      <label>评审类型 *<select v-model="form.reviewType"><option value="PCB">PCB布局布线评审</option><option value="SCHEMATIC">原理图评审</option></select></label>
      <label>{{ form.reviewType === 'PCB' ? 'PCB 名称 *' : '原理图名称 *' }}<input v-model.trim="form.designName" placeholder="根据评审类型填写" /></label>
      <label>任务名称 *<input v-model.trim="form.taskName" placeholder="请输入任务名称" /></label><label>设计者 *<select v-model.number="form.designerId" @change="selectDesigner"><option v-for="user in mockUsers" :key="user.id" :value="user.id">{{ user.displayName }}</option></select><small>系统提交设计者 ID，页面显示姓名：{{ form.designerName }}</small></label>
      <label>项目名称 *<input v-model.trim="form.projectName" placeholder="请输入项目名称" /></label><label>期望完成日期 *<input v-model="form.expectedCompletedDate" type="date" /></label>
      <label v-if="form.reviewType === 'PCB'">PCB 类型 *<select v-model="form.pcbType"><option value="">请选择</option><option v-for="item in pcbTypes" :key="item" :value="item">{{ item }}</option></select></label><div v-else class="empty-field" />
      <label>专家 / 组长 *<select v-model.number="form.expertLeaderId" @change="selectLeader"><option value="">请选择</option><option v-for="user in mockUsers.filter((item) => !item.roles.includes('DESIGNER'))" :key="user.id" :value="user.id">{{ user.displayName }}</option></select><small>{{ form.expertLeaderName || '请选择专家或组长' }}</small></label>
    </div>
    <label class="role-label">评审角色 *<div class="role-options"><label v-for="role in reviewRoles" :key="role.code" class="role-option"><input v-model="form.reviewRoles" type="checkbox" :value="role.code" /><span>{{ role.name }}</span><small>{{ role.code }}</small></label></div></label>
    <label>评审文件 *<input type="file" multiple @change="selectFiles" /><small>支持多选；可多次选择，保存或提交时会将所有文件一并关联到任务。</small></label>
    <div v-if="savedFiles.length || sourceFiles.length || uploadedFiles.length" class="file-list">
      <div v-for="file in savedFiles" :key="file.id" class="file-row"><span class="file-state saved">已保存</span><b>{{ file.fileName }}</b><small>{{ file.fileSize }} B</small></div>
      <div v-for="file in sourceFiles" :key="sourceFileKey(file)" class="file-row"><span class="file-state pending">待上传</span><b>{{ file.name }}</b><small>{{ file.size }} B</small><button class="btn mini" type="button" @click="removeSelectedFile(file)">移除</button></div>
      <div v-for="fileId in uploadedFiles" :key="fileId" class="file-row"><span class="file-state uploaded">已上传</span><small>文件 UUID：{{ fileId }}</small></div>
    </div>
    <label>评审描述<textarea v-model.trim="form.reviewDescription" rows="4" placeholder="请输入评审描述（可选）" /></label>
    <p v-if="error" class="form-error">{{ error }}</p><footer class="form-footer"><button class="btn" :disabled="saving" type="button" @click="save(false)">{{ editing ? '保存修改' : '保存' }}</button><button class="btn primary" :disabled="saving" type="submit">{{ saving ? '正在处理…' : (editing ? '保存并提交' : '提交评审任务') }}</button></footer>
  </form>
</template>

<style scoped>
.task-create-card{max-width:1400px}.create-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.inline-pair{display:grid;grid-template-columns:1fr 130px;gap:8px}.role-label{margin:5px 0 18px}.role-options{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px}.role-option{display:flex!important;flex-direction:column;gap:5px;border:1px solid #e1e2eb;border-radius:9px;padding:13px;background:#fff;min-height:80px}.role-option:has(input:checked){border-color:#7566db;background:#f6f4ff}.role-option input{width:auto}.role-option span{font-weight:700;color:#36394c}.role-option small{font-size:10px;color:#8b8e9d;word-break:break-all}.file-list{display:grid;gap:7px;margin:-7px 0 14px}.file-row{display:flex;align-items:center;gap:9px;min-height:34px;padding:7px 10px;border:1px solid #e3e5ee;border-radius:7px;background:#fafbfe}.file-row b{font-size:13px;color:#333b51}.file-row small{color:#7a8194;font-size:12px}.file-row .btn{margin-left:auto}.file-state{padding:3px 7px;border-radius:10px;font-size:11px;white-space:nowrap}.file-state.saved{color:#14834c;background:#e8faef}.file-state.pending{color:#b26a00;background:#fff5df}.file-state.uploaded{color:#4d5bc7;background:#eff0ff}@media(max-width:900px){.role-options{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.create-grid,.role-options{grid-template-columns:1fr}.inline-pair{grid-template-columns:1fr}.file-row{align-items:flex-start;flex-wrap:wrap}.file-row .btn{margin-left:0}}
</style>
