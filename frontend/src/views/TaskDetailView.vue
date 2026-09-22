<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import TaskFlow from '@/components/TaskFlow.vue'
import MutualCheckForm from '@/components/MutualCheckForm.vue'
import { reviewApi } from '@/api/review-api'
import { identity } from '@/stores/identity'
import type { Archive, AssignableReviewerRole, CheckItemCategory, CheckItemListItem, Opinion, OpinionSummary, Task } from '@/api/types'

const route = useRoute()
const taskId = computed(() => Number(route.params.taskId))
const activeTab = ref('overview')
const stageTabs = [
  ['overview', '任务信息'], ['opinions', '专家评审'], ['designer-reply', '设计者答复'], ['files', '上传工艺/结构图'],
  ['process-review', '工艺评审'], ['structure-review', '结构评审'], ['optional-reply', '设计者答复'],
  ['reviewers', '互检单分配'], ['check-items', '互检单'], ['mutual-reply', '设计者答复'], ['finish', '结束确认'], ['archive', '归档记录']
] as const
const schematicStageTabs = [
  ['overview', '任务信息'], ['reviewers', '互检单分配'], ['check-items', '互检单'], ['designer-reply', '设计者答复'],
  ['hardware-reviewers', '硬件专家分配'], ['opinions', '原理图评审'], ['optional-reply', '设计者答复'], ['finish', '流程结束'], ['archive', '归档记录']
] as const
const task = ref<Task>()
const opinions = ref<Opinion[]>([])
const opinionSummary = ref<OpinionSummary>()
const checkItemCategories = ref<CheckItemCategory[]>([])
const archive = ref<Archive>()
const assignableReviewerGroups = ref<AssignableReviewerRole[]>([])
const mockUsers = ref<Array<{ id: number; displayName: string; email: string; departmentName: string; roles: string[] }>>([])
const reviewerRole = ref('PCB_EXPERT')
const loading = ref(true)
const error = ref('')
const notice = ref('')
const opinionForm = reactive({ severity: 'GENERAL' as Opinion['severity'] })
const opinionListFilters = reactive({ severity: '', status: '' })
const opinionPagination = reactive({ pageNo: 1, pageSize: 10, total: 0 })
const opinionEditor = ref<HTMLElement>()
const screenshotEditor = ref<HTMLElement>()
const replyDrafts = reactive<Record<number, { replyType: 'ACCEPT' | 'REJECT'; reason: string }>>({})
const reviewerIds = ref('')
const selectedReviewerIds = ref<number[]>([])
const fileInput = ref<HTMLInputElement>()
const fileCategory = ref<'PROCESS_REVIEW' | 'SCHEMATIC_REVIEW' | 'MUTUAL_CHECK_REVIEW'>('PROCESS_REVIEW')
const selectedFile = ref<File>()
const structureFile = ref<File>()
const processFile = ref<File>()

const statusText = computed(() => task.value?.status === 'FINISHED' ? '归档已经冻结，所有内容只读。' : '当前详情根据后端任务状态实时加载。')
const visibleStageTabs = computed(() => task.value?.reviewType === 'SCHEMATIC' ? schematicStageTabs : stageTabs)
const mutualOpinions = computed(() => opinions.value.filter((item) => item.sourceType === 'MUTUAL_CHECK_ITEM'))
const rejectedOpinions = computed(() => opinions.value.filter((item) => item.status === 'CONFIRMED_REJECTED'))
const checkItems = computed(() => checkItemCategories.value.flatMap((category) => category.items))
const reviewWorkspaceTitle = computed(() => {
  if (['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab.value)) return '设计者答复'
  if (activeTab.value === 'process-review') return '工艺评审工作台'
  if (activeTab.value === 'structure-review') return '结构评审工作台'
  return task.value?.reviewType === 'SCHEMATIC' ? '原理图评审工作台' : '专家评审工作台'
})
const reviewerAssignmentAction = computed(() => {
  if (!task.value) return ''
  if (task.value.reviewType === 'SCHEMATIC') {
    return activeTab.value === 'hardware-reviewers' ? 'START_SCHEMATIC_EXPERT_REVIEW' : 'START_SCHEMATIC_MUTUAL_REVIEW'
  }
  return 'START_PCB_MUTUAL_REVIEW'
})
const assignableReviewers = computed(() => assignableReviewerGroups.value
  .find((group) => group.reviewRole === reviewerRole.value)?.reviewers ?? [])

function syncReviewerRole(): void {
  if (!task.value || !['reviewers', 'hardware-reviewers'].includes(activeTab.value)) return
  if (task.value.reviewType === 'SCHEMATIC') {
    reviewerRole.value = activeTab.value === 'hardware-reviewers' ? 'SCHEMATIC_HARDWARE_EXPERT' : 'SCHEMATIC_MUTUAL_CHECK'
    return
  }
  reviewerRole.value = 'PCB_MUTUAL_CHECK'
}

async function load(): Promise<void> {
  loading.value = true; error.value = ''; notice.value = ''
  try {
    const [taskData, opinionsData, summaryData, checkItemsData, users] = await Promise.all([reviewApi.getTask(taskId.value), reviewApi.listOpinions(taskId.value, opinionFilters()), reviewApi.getOpinionSummary(taskId.value), reviewApi.listCheckItems(taskId.value), reviewApi.listMockUsers()])
    task.value = taskData; opinions.value = normalizeOpinions(opinionsData.items); opinionSummary.value = summaryData; opinionPagination.total = opinionsData.total; opinionPagination.pageNo = opinionsData.pageNo; checkItemCategories.value = checkItemsData; mockUsers.value = users
    syncReviewerRole()
    if (taskData.status === 'FINISHED') archive.value = await reviewApi.getArchive(taskId.value)
    await loadAssignableReviewers()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载任务详情失败' } finally { loading.value = false }
}
function opinionFilters(): { severity?: string; status?: string; sourceType?: string; scene: 'REVIEW_WORKSPACE' | 'DESIGNER_REPLY'; pageNo: number; pageSize: number } {
  const filters = { severity: opinionListFilters.severity || undefined, status: opinionListFilters.status || undefined, pageNo: opinionPagination.pageNo, pageSize: opinionPagination.pageSize }
  if (['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab.value)) return { ...filters, scene: 'DESIGNER_REPLY' }
  if (activeTab.value === 'process-review') return { ...filters, sourceType: 'PROCESS_REVIEW', scene: 'REVIEW_WORKSPACE' }
  if (activeTab.value === 'structure-review') return { ...filters, sourceType: 'STRUCTURE_REVIEW', scene: 'REVIEW_WORKSPACE' }
  return { ...filters, sourceType: 'EXPERT_REVIEW', scene: 'REVIEW_WORKSPACE' }
}
function normalizeOpinions(items: Opinion[]): Opinion[] { return items.map((item) => ({ ...item, content: item.richText || item.content, replies: item.replies ?? [] })) }
async function refreshOpinions(): Promise<void> { try { const page = await reviewApi.listOpinions(taskId.value, opinionFilters()); opinions.value = normalizeOpinions(page.items); opinionPagination.total = page.total; opinionPagination.pageNo = page.pageNo } catch { /* 页面主数据已加载时不打断其他区域 */ } }
async function refreshOpinionSummary(): Promise<void> { try { opinionSummary.value = await reviewApi.getOpinionSummary(taskId.value) } catch { /* 不影响意见列表操作 */ } }
function resetOpinionPage(): void { opinionPagination.pageNo = 1; void refreshOpinions() }
function changeOpinionPage(pageNo: number): void { if (pageNo < 1 || pageNo > Math.ceil(opinionPagination.total / opinionPagination.pageSize)) return; opinionPagination.pageNo = pageNo; void refreshOpinions() }
async function loadAssignableReviewers(): Promise<void> {
  if (!task.value || !['reviewers', 'hardware-reviewers'].includes(activeTab.value)) {
    assignableReviewerGroups.value = []
    return
  }
  try {
    assignableReviewerGroups.value = await reviewApi.listAssignableReviewers(taskId.value)
    if (!assignableReviewerGroups.value.some((group) => group.reviewRole === reviewerRole.value)) {
      reviewerRole.value = assignableReviewerGroups.value[0]?.reviewRole || ''
    }
  } catch {
    assignableReviewerGroups.value = []
  }
}
watch(activeTab, async () => { syncReviewerRole(); await loadAssignableReviewers() })
watch(activeTab, () => { syncReviewerRole(); opinionPagination.pageNo = 1; void refreshOpinions() })
onMounted(load)

function setNotice(value: string): void { notice.value = value; window.setTimeout(() => { if (notice.value === value) notice.value = '' }, 3500) }
function openDesignerFileUpload(): void { activeTab.value = 'files' }
function remindDesignerWorkflow(): void { setNotice('请逐条提交待答复意见；全部闭环后可在上传阶段文件页推进下一流程。') }
async function raiseOpinion(): Promise<void> {
  const opinionContent = opinionEditor.value?.innerHTML.trim() ?? ''
  const screenshots = screenshotEditor.value?.innerHTML.trim() ?? ''
  if (![opinionContent, screenshots].some((item) => item && item !== '<br>')) { setNotice('请填写具体评审意见或粘贴问题截图。'); return }
  const content = `${opinionContent}${screenshots}`
  try {
    await reviewApi.raiseOpinion(taskId.value, { ...opinionForm, sourceType: currentOpinionSource(), content, richText: content })
    if (opinionEditor.value) opinionEditor.value.innerHTML = ''
    if (screenshotEditor.value) screenshotEditor.value.innerHTML = ''
    await refreshOpinions(); await refreshOpinionSummary(); setNotice('评审意见已提交。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '提交失败') }
}
function handleScreenshotPaste(event: ClipboardEvent): void {
  const files = Array.from(event.clipboardData?.files ?? []).filter((file) => file.type.startsWith('image/'))
  if (!files.length) return
  event.preventDefault()
  files.forEach((file) => { const reader = new FileReader(); reader.onload = () => { const dataUrl = String(reader.result); if (screenshotEditor.value) screenshotEditor.value.innerHTML += `<img src="${dataUrl}" alt="问题截图" />` }; reader.readAsDataURL(file) })
}
function currentOpinionSource(): Opinion['sourceType'] {
  if (activeTab.value === 'process-review') return 'PROCESS_REVIEW'
  if (activeTab.value === 'structure-review') return 'STRUCTURE_REVIEW'
  return 'EXPERT_REVIEW'
}
function draft(opinion: Opinion): { replyType: 'ACCEPT' | 'REJECT'; reason: string } { return replyDrafts[opinion.id] ??= { replyType: 'ACCEPT', reason: '' } }
function requiresReply(opinion: Opinion): boolean { return opinion.status === 'PENDING_REPLY' || opinion.status === 'CONFIRMED_REJECTED' }
function latestConfirmation(opinion: Opinion) { return opinion.replies.at(-1)?.confirmation }
async function replyOpinion(opinion: Opinion): Promise<void> { try { await reviewApi.replyOpinion(opinion.id, draft(opinion)); await refreshOpinions(); await refreshOpinionSummary(); setNotice('设计者答复已提交，等待提出人确认。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '答复失败') } }
async function confirmOpinion(opinion: Opinion, passed: boolean): Promise<void> { try { await reviewApi.confirmOpinion(opinion.id, { passed }); await refreshOpinions(); await refreshOpinionSummary(); setNotice(passed ? '已确认通过。' : '已退回设计者重新答复。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '确认失败') } }
async function finishTask(): Promise<void> { try { await reviewApi.transition(taskId.value, { action: 'FINISH', comment: '在结束确认页确认任务结束' }); await load(); setNotice('任务已结束，归档记录已冻结。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '任务结束失败') } }
async function startPcbOptionalReview(): Promise<void> { try { await reviewApi.transition(taskId.value, { action: 'START_PCB_OPTIONAL_REVIEW', comment: '工艺/结构图已上传，申请开启工艺/结构评审' }); await load(); activeTab.value = 'process-review'; setNotice('已开启工艺/结构评审。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '暂不能开启工艺/结构评审，请确认前序意见已全部确认通过且已上传所需文件。') } }
async function saveReviewers(): Promise<void> { const ids = selectedReviewerIds.value.length ? selectedReviewerIds.value : reviewerIds.value.split(',').map((value) => Number(value.trim())).filter((value) => value > 0); if (!ids.length) { setNotice(`请至少选择一名${activeTab.value === 'hardware-reviewers' ? '硬件专家' : '互检负责人'}。`); return } try { await reviewApi.transition(taskId.value, { action: reviewerAssignmentAction.value, assignedRole: reviewerRole.value, reviewerIds: ids, comment: activeTab.value === 'hardware-reviewers' ? '已分配硬件专家并开始原理图评审' : '已分配互检负责人并开启互检' }); await load(); setNotice(activeTab.value === 'hardware-reviewers' ? '硬件专家已分配，已进入原理图评审。' : '互检负责人已分配，已开启互检。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '分配或流程推进失败') } }
function userName(userId: number): string { return mockUsers.value.find((item) => item.id === userId)?.displayName ?? `用户 #${userId}` }
function reviewerRoleLabel(role: string): string { return ({ PCB_MUTUAL_CHECK: 'PCB 互检', SCHEMATIC_MUTUAL_CHECK: '原理图互检', SCHEMATIC_HARDWARE_EXPERT: '原理图硬件评审', SCHEMATIC_OTHER_EXPERT: '原理图其他评审' } as Record<string, string>)[role] || role }
function selectFile(event: Event): void { selectedFile.value = (event.target as HTMLInputElement).files?.[0] }
function selectPcbStageFile(kind: 'PROCESS' | 'STRUCTURE', event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (kind === 'PROCESS') processFile.value = file
  else structureFile.value = file
}
async function registerFile(): Promise<void> { if (!selectedFile.value) { setNotice('请选择要上传的文件。'); return } try { await reviewApi.uploadTaskFile(taskId.value, fileCategory.value, selectedFile.value); setNotice('文件已上传。'); selectedFile.value = undefined; if (fileInput.value) fileInput.value.value = '' } catch (cause) { setNotice(cause instanceof Error ? cause.message : '文件上传失败') } }
async function uploadAndStartOptionalReview(): Promise<void> {
  if (!processFile.value && !structureFile.value) { setNotice('请至少选择工艺图或结构图文件。'); return }
  try {
    await Promise.all([
      ...(structureFile.value ? [reviewApi.uploadTaskFile(taskId.value, 'STRUCTURE_REVIEW', structureFile.value)] : []),
      ...(processFile.value ? [reviewApi.uploadTaskFile(taskId.value, 'PROCESS_REVIEW', processFile.value)] : [])
    ])
    processFile.value = undefined; structureFile.value = undefined
    if (fileInput.value) fileInput.value.value = ''
    await startPcbOptionalReview()
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '文件上传或流程推进失败') }
}
async function download(fileId: number): Promise<void> { try { const blob = await reviewApi.downloadContent(taskId.value, fileId); const url = URL.createObjectURL(blob); const popup = window.open(url, '_blank', 'noopener'); if (!popup) { const anchor = document.createElement('a'); anchor.href = url; anchor.download = ''; anchor.click() }; window.setTimeout(() => URL.revokeObjectURL(url), 30_000) } catch (cause) { setNotice(cause instanceof Error ? cause.message : '下载失败') } }
async function downloadLatestReviewFile(): Promise<void> {
  const category = activeTab.value === 'process-review' ? 'PROCESS_REVIEW' : activeTab.value === 'structure-review' ? 'STRUCTURE_REVIEW' : 'TASK_CREATION'
  try {
    const file = (await reviewApi.latestFiles(taskId.value, category))[0]
    if (!file) { setNotice('暂未找到当前评审阶段的文件。'); return }
    await download(file.id)
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '下载失败') }
}
function format(value?: string): string { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function severityLabel(severity: Opinion['severity']): string { return ({ SERIOUS: '严重', GENERAL: '一般', MINOR: '轻微' } as Record<Opinion['severity'], string>)[severity] }
function statusForCheck(item: CheckItemListItem): string { return item.opinion?.result ?? 'PENDING' }
</script>

<template>
  <div v-if="loading" class="loading page-loading">正在加载任务详情…</div>
  <div v-else-if="error" class="api-error page-loading"><b>无法加载任务</b><span>{{ error }}</span><button class="btn primary" @click="load">重试</button></div>
  <template v-else-if="task">
    <div class="page-head detail-head"><div><div class="crumb">评审任务 / {{ task.projectName }}</div><h1>{{ task.taskName }}</h1></div><div><span class="tag">{{ task.reviewType === 'PCB' ? 'PCB布局布线评审' : '原理图评审' }}</span> <StatusTag :value="task.status" /></div></div>
    <section class="card task-summary"><div class="summary-grid"><div><span>项目</span><b>{{ task.projectName }}</b></div><div><span>评审类型</span><b>{{ task.reviewType === 'PCB' ? 'PCB布局布线评审' : '原理图评审' }}</b></div><div><span>设计者</span><b>{{ task.designerName }}</b></div><div><span>评审角色</span><b>{{ task.reviewRoles.join(' / ') }}</b></div></div><p class="flow-status">流程状态　<b><StatusTag :value="task.status" /></b></p><TaskFlow :status="task.status" :review-type="task.reviewType" /></section>
    <p v-if="notice" class="toast-message">{{ notice }}</p>
    <div class="detail-tabs"><button v-for="item in visibleStageTabs" :key="item[0]" :class="{ active: activeTab === item[0] }" @click="activeTab = item[0]">{{ item[1] }}</button></div>
    <section v-if="activeTab === 'overview'" class="detail-grid"><div class="card"><div class="section-head"><h2>任务信息</h2><RouterLink v-if="task.status === 'DRAFT' && task.designerId === identity.userId" :to="`/tasks/${task.id}/edit`" class="btn">编辑任务</RouterLink></div><div class="detail-list"><div><span>任务状态</span><StatusTag :value="task.status" /></div><div><span>设计名称</span><b>{{ task.designName }}</b></div><div><span>期望完成日期</span><b>{{ task.expectedCompletedDate }}</b></div><div><span>评审描述</span><b>{{ task.reviewDescription || '—' }}</b></div><div><span>意见总数</span><b>{{ opinions.length }}</b></div><div><span>互检项数量</span><b>{{ checkItems.length }}</b></div></div></div><div class="card"><h2>流程提示</h2><p class="notice">{{ statusText }}</p><p v-if="task.status !== 'FINISHED'" class="muted">流程动作需满足当前节点权限后方可提交。</p><p v-else class="success-text">任务已结束，已可在“归档记录”查看冻结的数据。</p></div></section>
    <section v-else-if="activeTab === 'finish'" class="tab-panel finish-panel">
      <div class="section-head"><div><h2>结束确认</h2><p>确认所有阶段人员均已处理，系统将冻结任务、意见、文件和邮件归档。</p></div><StatusTag :value="task.status" /></div>
      <div class="card finish-checklist">
        <div class="finish-row"><b>互检单</b><span>{{ checkItems.length && checkItems.every((item) => item.opinion != null) ? '已完成' : '待处理' }}</span></div>
        <div class="finish-row"><b>设计者答复</b><span>{{ opinions.some((item) => item.status === 'PENDING_REPLY' || item.status === 'CONFIRMED_REJECTED') ? '待处理' : '已完成' }}</span></div>
        <div class="finish-row"><b>专家确认</b><span>{{ opinions.some((item) => item.status === 'PENDING_CONFIRMATION') ? '待处理' : '已完成' }}</span></div>
        <div class="finish-row"><b>{{ task.reviewType === 'SCHEMATIC' ? '原理图专家评审' : 'PCB 评审流程' }}</b><span>{{ task.status === 'PENDING_FINISH_CONFIRMATION' || task.status === 'FINISHED' ? '已完成' : '待处理' }}</span></div>
        <footer class="form-footer"><button class="btn primary" :disabled="task.status !== 'PENDING_FINISH_CONFIRMATION'" @click="finishTask">确认任务结束</button></footer>
      </div>
      <p v-if="task.status !== 'PENDING_FINISH_CONFIRMATION' && task.status !== 'FINISHED'" class="notice">当前尚未进入结束确认节点；待评审人员、意见闭环和阶段流转完成后方可结束。</p>
    </section>
    <section v-else-if="['opinions', 'designer-reply', 'process-review', 'structure-review', 'optional-reply', 'mutual-reply'].includes(activeTab)" class="tab-panel" :class="{ 'designer-reply-workspace': ['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab) }">
      <div class="workspace-heading"><h2 class="workspace-title">{{ reviewWorkspaceTitle }}</h2><div v-if="['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab)" class="designer-actions"><button class="btn primary" @click="openDesignerFileUpload">↑ 上传最新版本文件</button><button class="btn primary" @click="remindDesignerWorkflow">提交流程</button></div></div>
      <section v-if="!['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab)" class="card review-file-card">
        <div><span>设计者最新上传文件</span><b>{{ task.designName }}</b><small>当前评审文件 · {{ task.reviewType === 'PCB' ? 'PCB 布局布线' : '原理图' }}</small></div>
        <button type="button" class="btn primary review-file-download" @click="downloadLatestReviewFile">↓ 下载最新版本文件</button>
      </section>
      <div class="two-column">
        <div v-if="!['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab)" class="card review-submit-card">
          <div class="review-submit-grid"><div class="screenshot-panel"><h2>问题截图</h2><div ref="screenshotEditor" class="screenshot-paste" contenteditable="true" data-placeholder="Ctrl + V 粘贴问题截图" @paste="handleScreenshotPaste" /></div><div class="review-opinion-panel"><header><h2>评审意见</h2><button class="btn primary" @click="raiseOpinion">提交评审意见</button></header><div class="review-content-field"><label>问题等级 *<select v-model="opinionForm.severity"><option value="SERIOUS">严重</option><option value="GENERAL">一般</option><option value="MINOR">轻微</option></select></label><label>具体评审意见 *<div ref="opinionEditor" class="rich-opinion-editor" contenteditable="true" data-placeholder="请输入具体、可执行的评审意见" /></label></div></div></div>
        </div>
        <div v-else class="card designer-opinion-board"><div class="section-head"><div><h2>意见看板 <span class="board-help">?</span></h2></div></div><div class="designer-stat-grid"><div class="reply-stat pending-reply"><span>待答复意见</span><b>{{ opinions.filter((item) => item.status === 'PENDING_REPLY').length }}</b></div><div class="reply-stat pending-confirm"><span>待确认意见</span><b>{{ opinions.filter((item) => item.status === 'PENDING_CONFIRMATION').length }}</b></div><div class="reply-stat rejected-stat"><span>确认不通过</span><b>{{ rejectedOpinions.length }}</b></div><div class="reply-stat confirmed-stat"><span>确认通过</span><b>{{ opinions.filter((item) => item.status === 'CONFIRMED_PASS').length }}</b></div></div><div class="designer-insights"><span>设计者 {{ opinions.filter((item) => item.status === 'PENDING_REPLY' || item.status === 'CONFIRMED_REJECTED').length }} 条未答复意见</span><span>{{ opinions.filter((item) => item.status === 'PENDING_CONFIRMATION').length }} 条待专家确认</span><span v-if="rejectedOpinions.length">{{ rejectedOpinions.length }} 条意见需重新答复</span></div></div>
      </div>
      <section class="card opinion-section"><header class="opinion-list-head"><h2>意见列表</h2><div class="opinion-filter"><select v-model="opinionListFilters.severity" @change="resetOpinionPage"><option value="">全部问题等级</option><option value="SERIOUS">严重</option><option value="GENERAL">一般</option><option value="MINOR">轻微</option></select><select v-model="opinionListFilters.status" @change="resetOpinionPage"><option value="">全部状态</option><option value="PENDING_REPLY">待答复</option><option value="PENDING_CONFIRMATION">待确认</option><option value="CONFIRMED_PASS">确认通过</option><option value="CONFIRMED_REJECTED">确认不通过</option></select><span class="tag">{{ opinionPagination.total }} 条</span></div></header><div class="opinion-list compact-opinion-list"><article v-for="opinion in opinions" :key="opinion.id" class="opinion-row" :class="{ 'retry-required': opinion.status === 'CONFIRMED_REJECTED' }"><div class="opinion-main"><div class="opinion-meta"><span class="severity-chip" :class="opinion.severity.toLowerCase()">{{ severityLabel(opinion.severity) }}</span><StatusTag :value="opinion.status" /><span>提出人：{{ opinion.raisedByName || userName(opinion.raisedBy) }}</span><span>提出时间：{{ format(opinion.createdAt) }}</span></div><div class="rich-opinion-content" v-html="opinion.content" /><div v-if="opinion.replies.length" class="reply-history"><b>设计者答复历史</b><div v-for="reply in opinion.replies" :key="reply.id" class="reply-history-row"><div>第 {{ reply.replyNo }} 轮：{{ reply.replyType === 'ACCEPT' ? '接受' : '不接受' }}｜{{ reply.reason || '未填写补充说明' }} <small>{{ format(reply.repliedAt) }}</small></div><div v-if="reply.confirmation" class="confirmation-history" :class="reply.confirmation.passed ? 'passed' : 'rejected'">专家{{ reply.confirmation.passed ? '确认通过' : '确认不通过' }}：{{ reply.confirmation.comment || '未填写确认意见' }} <small>{{ format(reply.confirmation.confirmedAt) }}</small></div><div v-else class="confirmation-history pending">等待专家确认</div></div></div><div v-if="opinion.status === 'CONFIRMED_REJECTED'" class="retry-feedback"><b>专家未确认通过，请重新答复</b><span>退回原因：{{ latestConfirmation(opinion)?.comment || '专家未填写退回说明' }}</span></div><div v-if="task.designerId === identity.userId && requiresReply(opinion)" class="inline-form reply-editor"><select v-model="draft(opinion).replyType"><option value="ACCEPT">接受并修改</option><option value="REJECT">不接受</option></select><input v-model="draft(opinion).reason" :placeholder="opinion.status === 'CONFIRMED_REJECTED' ? '请填写本次重新答复说明' : '答复说明'" /><button class="btn primary" @click="replyOpinion(opinion)">{{ opinion.status === 'CONFIRMED_REJECTED' ? '重新提交答复' : '提交答复' }}</button></div><div v-if="opinion.raisedBy === identity.userId && opinion.status === 'PENDING_CONFIRMATION'" class="action-row"><button class="btn compact" @click="confirmOpinion(opinion, true)">通过</button><button class="btn compact danger" @click="confirmOpinion(opinion, false)">不通过</button></div></div></article><div v-if="!opinions.length" class="empty">暂无符合筛选条件的评审意见</div></div><footer v-if="opinionPagination.total > opinionPagination.pageSize" class="opinion-pagination"><button class="btn compact" :disabled="opinionPagination.pageNo <= 1" @click="changeOpinionPage(opinionPagination.pageNo - 1)">上一页</button><span>第 {{ opinionPagination.pageNo }} / {{ Math.ceil(opinionPagination.total / opinionPagination.pageSize) }} 页</span><button class="btn compact" :disabled="opinionPagination.pageNo >= Math.ceil(opinionPagination.total / opinionPagination.pageSize)" @click="changeOpinionPage(opinionPagination.pageNo + 1)">下一页</button></footer></section></section>
    <section v-else-if="activeTab === 'check-items'" class="tab-panel"><MutualCheckForm :task-id="taskId" :categories="checkItemCategories" @submitted="load" /></section>
    <section v-else-if="activeTab === 'reviewers' || activeTab === 'hardware-reviewers'" class="tab-panel"><div class="section-head"><div><h2>{{ activeTab === 'hardware-reviewers' ? '硬件专家分配' : '互检单负责人分配' }}</h2><p>{{ activeTab === 'hardware-reviewers' ? '由硬件开发部经理选择一名或多名硬件专家进行原理图评审。' : '选择一名或多名互检负责人；多人须全部完成后，阶段才算完成。' }}</p></div><StatusTag value="PENDING" /></div><div class="card"><div v-if="assignableReviewerGroups.length > 1" class="assignment-role-tabs"><button v-for="group in assignableReviewerGroups" :key="group.reviewRole" class="btn compact" :class="{ primary: reviewerRole === group.reviewRole }" @click="reviewerRole = group.reviewRole; selectedReviewerIds = []">{{ reviewerRoleLabel(group.reviewRole) }}</button></div><div class="reviewer-picker"><label v-for="user in assignableReviewers" :key="user.userId" class="reviewer-option"><input v-model="selectedReviewerIds" type="checkbox" :value="user.userId" /><span><b>{{ user.displayName }}</b><small>{{ user.employeeNo }} · {{ user.departmentName }}</small></span></label><div v-if="!assignableReviewers.length" class="empty">当前职责下暂无已配置且可用的白名单人员</div></div><footer class="form-footer"><button class="btn primary" :disabled="!assignableReviewers.length" @click="saveReviewers()">{{ activeTab === 'hardware-reviewers' ? '分配专家并开始评审' : '分配并开启互检' }}</button></footer></div></section>
    <section v-else-if="activeTab === 'files'" class="tab-panel stage-file-page"><div class="section-head stage-file-heading"><div><h2>上传工艺/结构图</h2></div><StatusTag value="PENDING" /></div><div class="card stage-upload-card"><div class="stage-upload-grid"><label class="stage-file-choice"><span class="file-type-tag">ZIP</span><b>结构图文件 *</b><small>上传结构评审使用的压缩文件。</small><input type="file" accept=".zip,.rar,.7z" @change="selectPcbStageFile('STRUCTURE', $event)" /><em>{{ structureFile?.name || '未选择任何文件' }}</em></label><label class="stage-file-choice"><span class="file-type-tag">ZIP</span><b>工艺图文件 *</b><small>上传工艺评审使用的压缩文件。</small><input ref="fileInput" type="file" accept=".zip,.rar,.7z" @change="selectPcbStageFile('PROCESS', $event)" /><em>{{ processFile?.name || '未选择任何文件' }}</em></label></div><footer class="form-footer"><button v-if="task.reviewType === 'PCB'" class="btn primary" :disabled="task.status !== 'PCB_DESIGNER_REPLYING'" @click="uploadAndStartOptionalReview">上传并进入下一阶段</button><button v-else class="btn primary" @click="registerFile">上传文件</button></footer></div></section>
    <section v-else class="tab-panel"><div v-if="!archive" class="card unsupported"><div class="unsupported-icon">⌁</div><h2>任务尚未结束</h2><p>任务完成时，系统将冻结流程节点、阶段文件和邮件记录。</p></div><template v-else><div class="card"><div class="section-head"><div><h2>一、流程节点</h2><p>仅展示节点时间、节点名称、操作人姓名和流转意见，不包含评审意见。</p></div></div><table class="data-table"><thead><tr><th>节点时间</th><th>节点名称</th><th>操作人姓名</th><th>流转意见</th></tr></thead><tbody><tr v-for="(item, index) in archive.flowNodes" :key="`${item.occurredAt}-${item.stageName}-${index}`"><td>{{ format(item.occurredAt) }}</td><td>{{ item.stageName }}</td><td>{{ item.operatorName }}</td><td>{{ item.content }}</td></tr><tr v-if="!archive.flowNodes.length"><td colspan="4" class="empty">暂无流程节点</td></tr></tbody></table></div><div class="card"><div class="section-head"><div><h2>二、阶段文件</h2><p>展示归档时冻结的各阶段文件记录。</p></div></div><table class="data-table"><thead><tr><th>流程节点</th><th>文件名称</th><th>格式</th><th>大小</th><th>上传人</th><th>上传时间</th><th>操作</th></tr></thead><tbody><tr v-for="file in archive.stageFiles" :key="file.fileId"><td>{{ file.stageName }}</td><td>{{ file.fileName }}</td><td>{{ file.fileFormat || '-' }}</td><td>{{ file.fileSize == null ? '-' : file.fileSize + ' B' }}</td><td>{{ file.uploaderName }}</td><td>{{ format(file.uploadedAt) }}</td><td><button class="btn mini primary" @click="download(file.fileId)">下载</button></td></tr><tr v-if="!archive.stageFiles.length"><td colspan="7" class="empty">暂无阶段文件</td></tr></tbody></table></div><div class="card"><div class="section-head"><div><h2>三、邮件记录</h2><p>展示该任务流程中已投递的邮件记录。</p></div></div><table class="data-table"><thead><tr><th>时间</th><th>场景</th><th>收件人</th><th>状态</th></tr></thead><tbody><tr v-for="(mail, index) in archive.mailRecords" :key="`${mail.sentAt}-${mail.recipient}-${index}`"><td>{{ format(mail.sentAt) }}</td><td>{{ mail.scenario }}</td><td>{{ mail.recipient }}</td><td>{{ mail.deliveryStatus || mail.status }}</td></tr><tr v-if="!archive.mailRecords.length"><td colspan="4" class="empty">暂无邮件记录</td></tr></tbody></table></div></template></section>
  </template>
</template>

<style scoped>
.workspace-title{margin:0 0 10px;font-size:16px}
.workspace-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;min-height:34px}
.designer-actions{display:flex;justify-content:flex-end;gap:10px}
.designer-actions .btn{padding:8px 13px}
.opinion-pagination{display:flex;justify-content:flex-end;align-items:center;gap:10px;padding-top:16px;color:#72778b;font-size:13px}
.review-file-card{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:20px;margin-bottom:0}
.review-file-card span,.review-file-card small{display:block;color:#878b9c;font-size:12px}
.review-file-card b{display:block;margin:6px 0;color:#24283c;font-size:16px}
.review-file-download{box-shadow:0 6px 14px rgba(102,87,217,.22)}
.review-submit-card{grid-column:1/-1;padding:18px}
.assignment-role-tabs{display:flex;gap:8px;margin-bottom:14px}
.review-submit-grid{display:grid;grid-template-columns:360px minmax(0,1fr);gap:20px}
.screenshot-panel{padding:14px;border:1px solid #dfe3ee;border-radius:11px;background:#fbfcff}
.screenshot-panel h2,.review-opinion-panel h2{margin:0;font-size:15px}
.screenshot-paste{display:grid;place-items:center;min-height:260px;margin-top:12px;padding:14px;border:1px dashed #bec9df;border-radius:8px;background:#fff;outline:0;color:#66708c;line-height:1.7}
.screenshot-paste:empty:before{color:#68728d;font-size:13px;content:attr(data-placeholder)}
.review-opinion-panel{display:grid;align-content:start;gap:16px}
.review-opinion-panel header{display:flex;align-items:center;justify-content:space-between;gap:14px;padding-bottom:12px;border-bottom:1px solid #e6e8ef}
.review-content-field{display:grid;align-content:start;gap:16px}
.rich-opinion-editor{min-height:158px;padding:13px;border:1px solid #d8deea;border-radius:8px;background:#fafbfe;outline:none;line-height:1.7}
.rich-opinion-editor:empty:before{color:#727a94;font-size:13px;content:attr(data-placeholder)}
.screenshot-paste :deep(img),.rich-opinion-content :deep(img){display:block;max-width:100%;max-height:260px;margin:8px 0;border-radius:5px;object-fit:contain}
.retry-required{border-color:#ffcbc8;background:linear-gradient(90deg,#fff 0%,#fff8f7 100%)}
.retry-feedback{display:grid;gap:5px;margin:12px 0;padding:10px 12px;border-left:3px solid #ef5350;border-radius:4px;background:#fff2f1;color:#a52d2a;font-size:13px}
.retry-feedback span{color:#76524f}.reply-editor{margin-top:14px}.retry-notice{color:#b6403f;background:#fff2f1;border-color:#ffd3d0}.rejected-stat{border-color:#ffcbc8!important;background:#fff8f7}.rejected-stat b{color:#e34a45!important}
.designer-opinion-board{grid-column:1/-1;padding:18px}.designer-opinion-board .section-head{margin-bottom:18px}.board-help{display:inline-grid;place-items:center;width:17px;height:17px;margin-left:4px;border:1px solid #8c8fb0;border-radius:50%;color:#6259bc;font-size:11px;font-weight:500;vertical-align:1px}.designer-stat-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;padding-bottom:10px;border-bottom:1px solid #ecebf1}.reply-stat{display:flex;align-items:center;justify-content:space-between;min-height:76px;padding:14px 12px;border:1px solid #dfe2ec;border-radius:10px;color:#4f5571;font-size:14px}.reply-stat b{font-size:24px;line-height:1;color:#5a51bd}.pending-reply b,.rejected-stat b{color:#e14650!important}.pending-confirm b{color:#ca7900}.designer-insights{display:flex;flex-wrap:wrap;gap:10px;margin-top:10px}.designer-insights span{min-width:240px;padding:10px 11px;border:1px solid #e4e2f1;border-radius:8px;background:#faf9ff;color:#4d5269;font-size:13px;font-weight:600}
.designer-reply-workspace .reply-editor{grid-template-columns:160px minmax(0,1fr);max-width:100%}.designer-reply-workspace .reply-editor input{display:none}.designer-reply-workspace .reply-editor .btn{justify-self:end}.rich-opinion-content :deep(img){float:right;width:148px!important;height:94px!important;max-width:32%;margin:-4px 0 10px 18px!important;object-fit:cover}.opinion-main::after{display:block;clear:both;content:''}
.stage-file-page{gap:16px}.stage-file-heading{margin:0}.stage-file-heading h2{margin:0;font-size:16px}.stage-upload-card{padding:18px}.stage-upload-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.stage-file-choice{position:relative;display:grid;gap:10px;min-height:132px;padding:18px;border:1px solid #dce2ed;border-radius:11px;background:#fbfcff}.stage-file-choice b{font-size:15px;color:#24283c}.stage-file-choice small{color:#73798e;font-size:13px}.stage-file-choice input{width:auto!important;max-width:100%;padding:0!important;border:0!important;border-radius:0!important;background:transparent!important;box-shadow:none!important}.stage-file-choice em{position:absolute;right:18px;bottom:19px;max-width:56%;overflow:hidden;color:#696f82;font-size:12px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.file-type-tag{position:absolute;right:16px;top:17px;padding:4px 8px;border-radius:14px;background:#edf5ff;color:#2878d7;font-size:11px}.stage-upload-card .form-footer{margin-top:16px;padding-top:16px}.stage-file-history{display:none}
.opinion-section{padding:0}.opinion-list-head{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:18px 20px;border-bottom:1px solid #ebeaf0}.opinion-list-head h2{margin:0;font-size:16px}.opinion-filter{display:flex;align-items:center;gap:9px}.opinion-filter select{padding:7px 9px;border:1px solid #dfe1e9;border-radius:7px;background:#fff;color:#4e5366}.compact-opinion-list{gap:0}.opinion-row{padding:18px 20px;border-bottom:1px solid #ececf2}.opinion-row:last-child{border-bottom:0}.rich-opinion-content{min-height:30px;margin:10px 0 0;color:#25283a;font-size:14px;font-weight:600;line-height:1.7}.opinion-meta{display:flex;align-items:center;flex-wrap:wrap;gap:7px;color:#84889a;font-size:12px}.severity-chip{display:inline-flex;align-items:center;padding:3px 8px;border-radius:14px;background:#eaf3ff;color:#2874dc;font-size:12px}.severity-chip.serious{background:#fff0f0;color:#e14545}.severity-chip.minor{background:#f3f3f7;color:#73798a}.reply-history-row{margin-top:8px;padding-top:8px;border-top:1px solid #e6e3f5}.reply-history-row:first-of-type{margin-top:5px}.confirmation-history{margin-top:5px;padding:4px 8px;border-radius:5px;font-size:12px}.confirmation-history.passed{background:#ebf8ef;color:#278355}.confirmation-history.rejected{background:#fff0ef;color:#c64a44}.confirmation-history.pending{background:#f2f3f7;color:#74798b}
@media(max-width:820px){.workspace-heading{align-items:flex-start;flex-direction:column}.designer-actions{width:100%;justify-content:flex-start}.review-file-card{align-items:flex-start;flex-direction:column}.review-submit-grid,.designer-stat-grid,.stage-upload-grid{grid-template-columns:1fr}.screenshot-paste{min-height:210px}.opinion-list-head{align-items:flex-start;flex-direction:column}.opinion-filter{width:100%;flex-wrap:wrap}.opinion-filter select{flex:1;min-width:130px}.designer-insights span{min-width:0;width:100%}.rich-opinion-content :deep(img){float:none;max-width:100%;margin:8px 0!important}}
</style>
