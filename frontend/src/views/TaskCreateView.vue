<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { reviewApi } from '@/api/review-api'
import { identity } from '@/stores/identity'

const router = useRouter()
const form = reactive({ reviewType: 'PCB', taskName: '', projectName: '', designName: '', designerId: identity.userId, designerName: `设计者#${identity.userId}`, expectedCompletedDate: '', pcbType: '', expertLeaderId: '', expertLeaderName: '', reviewRoles: [] as string[], reviewDescription: '' })
const sourceFiles = ref<File[]>([])
const pcbTypes = ref<string[]>(['BMU板', 'BSU板', '分流器板', '高压板', '转接板', '储能板', '其他'])
const reviewRoles = ref<Array<{ code: string; name: string }>>([])
const mockUsers = ref<Array<{ id: number; displayName: string; roles: string[] }>>([])
const saving = ref(false)
const error = ref('')

watch(() => form.reviewType, (type) => { if (type === 'SCHEMATIC') form.pcbType = '' })
onMounted(async () => { try { const [options, users] = await Promise.all([reviewApi.getTaskOptions(), reviewApi.listMockUsers()]); pcbTypes.value = options.pcbTypes; reviewRoles.value = options.reviewRoles; mockUsers.value = users; const currentUser = users.find((user) => user.id === identity.userId); if (currentUser) form.designerName = currentUser.displayName } catch { error.value = '字典或用户目录加载失败，已保留默认选项。' } })
function selectDesigner(): void { const user = mockUsers.value.find((item) => item.id === Number(form.designerId)); if (user) form.designerName = user.displayName }
function selectLeader(): void { const user = mockUsers.value.find((item) => item.id === Number(form.expertLeaderId)); if (user) form.expertLeaderName = user.displayName }
function selectFiles(event: Event): void { sourceFiles.value = Array.from((event.target as HTMLInputElement).files ?? []) }
async function save(submitNow: boolean): Promise<void> {
  error.value = ''; saving.value = true
  try {
    const task = await reviewApi.createTask({ reviewType: form.reviewType as 'PCB' | 'SCHEMATIC', taskName: form.taskName, projectName: form.projectName, designerId: Number(form.designerId), designerName: form.designerName, designName: form.designName, pcbType: form.pcbType || undefined, expectedCompletedDate: form.expectedCompletedDate, expertLeaderId: Number(form.expertLeaderId), expertLeaderName: form.expertLeaderName, reviewRoles: form.reviewRoles, reviewDescription: form.reviewDescription || undefined }, sourceFiles.value, submitNow)
    await router.push(`/tasks/${task.id}`)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败，请检查服务端返回的字段错误。' } finally { saving.value = false }
}
</script>

<template>
  <div class="page-head"><div><h1>创建评审任务</h1><p>保存草稿或提交评审；必填规则由后端统一校验并返回准确提示。</p></div></div>
  <form class="card form-card task-create-card" @submit.prevent="save(true)"><div class="notice">创建后会按照评审类型和角色进入对应流程。文件服务当前为本地 Mock，支持一次选择多个文件并登记元数据。</div>
    <div class="form-grid create-grid">
      <label>评审类型 *<select v-model="form.reviewType"><option value="PCB">PCB布局布线评审</option><option value="SCHEMATIC">原理图评审</option></select></label>
      <label>{{ form.reviewType === 'PCB' ? 'PCB 名称 *' : '原理图名称 *' }}<input v-model.trim="form.designName" placeholder="根据评审类型填写" /></label>
      <label>任务名称 *<input v-model.trim="form.taskName" placeholder="请输入任务名称" /></label><label>设计者 *<select v-model.number="form.designerId" @change="selectDesigner"><option v-for="user in mockUsers" :key="user.id" :value="user.id">{{ user.displayName }}</option></select><small>系统提交设计者 ID，页面显示姓名：{{ form.designerName }}</small></label>
      <label>项目名称 *<input v-model.trim="form.projectName" placeholder="请输入项目名称" /></label><label>期望完成日期 *<input v-model="form.expectedCompletedDate" type="date" /></label>
      <label v-if="form.reviewType === 'PCB'">PCB 类型 *<select v-model="form.pcbType"><option value="">请选择</option><option v-for="item in pcbTypes" :key="item" :value="item">{{ item }}</option></select></label><div v-else class="empty-field" />
      <label>专家 / 组长 *<select v-model.number="form.expertLeaderId" @change="selectLeader"><option value="">请选择</option><option v-for="user in mockUsers.filter((item) => !item.roles.includes('DESIGNER'))" :key="user.id" :value="user.id">{{ user.displayName }}</option></select><small>{{ form.expertLeaderName || '请选择专家或组长' }}</small></label>
    </div>
    <label class="role-label">评审角色 *<div class="role-options"><label v-for="role in reviewRoles" :key="role.code" class="role-option"><input v-model="form.reviewRoles" type="checkbox" :value="role.code" /><span>{{ role.name }}</span><small>{{ role.code }}</small></label></div></label>
    <label>评审文件 *<input type="file" multiple @change="selectFiles" /><small>{{ sourceFiles.length ? `已选择 ${sourceFiles.length} 个文件：${sourceFiles.map(item => item.name).join('、')}` : '支持多个文件上传' }}</small></label>
    <label>评审描述<textarea v-model.trim="form.reviewDescription" rows="4" placeholder="请输入评审描述（可选）" /></label>
    <p v-if="error" class="form-error">{{ error }}</p><footer class="form-footer"><button class="btn" :disabled="saving" type="button" @click="save(false)">保存</button><button class="btn primary" :disabled="saving" type="submit">{{ saving ? '正在处理…' : '提交评审任务' }}</button></footer>
  </form>
</template>

<style scoped>
.task-create-card{max-width:1400px}.create-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.inline-pair{display:grid;grid-template-columns:1fr 130px;gap:8px}.role-label{margin:5px 0 18px}.role-options{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px}.role-option{display:flex!important;flex-direction:column;gap:5px;border:1px solid #e1e2eb;border-radius:9px;padding:13px;background:#fff;min-height:80px}.role-option:has(input:checked){border-color:#7566db;background:#f6f4ff}.role-option input{width:auto}.role-option span{font-weight:700;color:#36394c}.role-option small{font-size:10px;color:#8b8e9d;word-break:break-all}@media(max-width:900px){.role-options{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.create-grid,.role-options{grid-template-columns:1fr}.inline-pair{grid-template-columns:1fr}}
</style>
