<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import TaskFlow from '@/components/TaskFlow.vue'
import BoardPreview from '@/components/BoardPreview.vue'
import MutualCheckForm from '@/components/MutualCheckForm.vue'
import { reviewApi } from '@/api/review-api'
import { identity } from '@/stores/identity'
import type { Archive, CheckItem, Opinion, Reviewer, Task } from '@/api/types'

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
const checkItems = ref<CheckItem[]>([])
const archive = ref<Archive>()
const reviewers = ref<Reviewer[]>([])
const mockUsers = ref<Array<{ id: number; displayName: string; email: string; departmentName: string; roles: string[] }>>([])
const reviewerRole = ref('PCB_EXPERT')
const loading = ref(true)
const error = ref('')
const notice = ref('')
const opinionForm = reactive({ sourceType: 'EXPERT_REVIEW' as Opinion['sourceType'], content: '', fileVersionId: undefined as number | undefined })
const opinionImageInput = ref<HTMLInputElement>()
const opinionImages = ref<File[]>([])
const replyDrafts = reactive<Record<number, { replyType: 'ACCEPT' | 'REJECT'; reason: string; fileVersionId?: number }>>({})
const reviewerIds = ref('')
const selectedReviewerIds = ref<number[]>([])
const fileInput = ref<HTMLInputElement>()
const fileKey = ref('')
const fileCategory = ref('PCB_SCHEMATIC')
const selectedFile = ref<File>()

const statusText = computed(() => task.value?.status === 'FINISHED' ? '归档已经冻结，所有内容只读。' : '当前详情根据后端任务状态实时加载。')
const visibleStageTabs = computed(() => task.value?.reviewType === 'SCHEMATIC' ? schematicStageTabs : stageTabs)
const mutualOpinions = computed(() => opinions.value.filter((item) => item.sourceType === 'MUTUAL_CHECK_ITEM'))
const reviewWorkspaceTitle = computed(() => {
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
    const [taskData, opinionsData, checkItemsData, users] = await Promise.all([reviewApi.getTask(taskId.value), reviewApi.listOpinions(taskId.value), reviewApi.listCheckItems(taskId.value), reviewApi.listMockUsers()])
    task.value = taskData; opinions.value = opinionsData; checkItems.value = checkItemsData; mockUsers.value = users
    syncReviewerRole()
    if (taskData.status === 'FINISHED') archive.value = await reviewApi.getArchive(taskId.value)
    await loadReviewers()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载任务详情失败' } finally { loading.value = false }
}
async function loadReviewers(): Promise<void> { try { reviewers.value = await reviewApi.listReviewers(taskId.value, reviewerRole.value) } catch { reviewers.value = [] } }
watch(reviewerRole, loadReviewers)
watch(activeTab, syncReviewerRole)
onMounted(load)

function setNotice(value: string): void { notice.value = value; window.setTimeout(() => { if (notice.value === value) notice.value = '' }, 3500) }
async function raiseOpinion(): Promise<void> {
  if (!opinionForm.content.trim()) { setNotice('请填写具体评审意见。'); return }
  try {
    const attachmentFileIds: number[] = []
    for (const [index, file] of opinionImages.value.entries()) {
      const session = await reviewApi.createUploadSession(taskId.value, 'OPINION_ATTACHMENT')
      const registered = await reviewApi.registerFile(taskId.value, { uploadSessionId: session.uploadSessionId, category: 'OPINION_ATTACHMENT', businessFileKey: `OPINION_${Date.now()}_${index}`, fileName: file.name, fileSize: file.size, md5: await checksum(file) })
      attachmentFileIds.push(registered.id)
    }
    await reviewApi.raiseOpinion(taskId.value, { ...opinionForm, content: opinionForm.content.trim(), attachmentFileIds })
    opinionForm.content = ''; opinionImages.value = []; if (opinionImageInput.value) opinionImageInput.value.value = ''
    opinions.value = await reviewApi.listOpinions(taskId.value); setNotice('评审意见已提交。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '提交失败') }
}
function draft(opinion: Opinion): { replyType: 'ACCEPT' | 'REJECT'; reason: string; fileVersionId?: number } { return replyDrafts[opinion.id] ??= { replyType: 'ACCEPT', reason: '' } }
async function replyOpinion(opinion: Opinion): Promise<void> { try { await reviewApi.replyOpinion(opinion.id, draft(opinion)); opinions.value = await reviewApi.listOpinions(taskId.value); setNotice('设计者答复已提交，等待提出人确认。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '答复失败') } }
async function confirmOpinion(opinion: Opinion, passed: boolean): Promise<void> { try { await reviewApi.confirmOpinion(opinion.id, { passed }); opinions.value = await reviewApi.listOpinions(taskId.value); setNotice(passed ? '已确认通过。' : '已退回设计者重新答复。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '确认失败') } }
async function finishTask(): Promise<void> { try { await reviewApi.transition(taskId.value, { action: 'FINISH', version: task.value!.version, comment: '在结束确认页确认任务结束' }); await load(); setNotice('任务已结束，归档记录已冻结。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '任务结束失败') } }
async function saveCheckItem(item: CheckItem): Promise<void> { try { const result = (document.getElementById(`check-result-${item.id}`) as HTMLSelectElement).value; const comment = (document.getElementById(`check-comment-${item.id}`) as HTMLInputElement).value; const linkedOpinionId = Number((document.getElementById(`check-opinion-${item.id}`) as HTMLSelectElement).value) || undefined; await reviewApi.submitCheckItem(taskId.value, item.id, { result, comment, linkedOpinionId, version: item.version }); checkItems.value = await reviewApi.listCheckItems(taskId.value); setNotice('检查项已保存。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '保存失败') } }
async function saveReviewers(reassign = false): Promise<void> { const ids = selectedReviewerIds.value.length ? selectedReviewerIds.value : reviewerIds.value.split(',').map((value) => Number(value.trim())).filter((value) => value > 0); if (!ids.length) { setNotice(`请至少选择一名${activeTab.value === 'hardware-reviewers' ? '硬件专家' : '互检负责人'}。`); return } try { reviewers.value = await reviewApi.assignReviewers(taskId.value, reviewerRole.value, ids, reassign); if (!reassign && reviewerAssignmentAction.value) { await reviewApi.transition(taskId.value, { action: reviewerAssignmentAction.value, version: task.value!.version, comment: activeTab.value === 'hardware-reviewers' ? '已分配硬件专家并开始原理图评审' : '已分配互检负责人并开启互检' }); await load(); setNotice(activeTab.value === 'hardware-reviewers' ? '硬件专家已分配，已进入原理图评审。' : '互检负责人已分配，已开启互检。'); return } setNotice('评审人员已改派。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '分配或流程推进失败') } }
function userName(userId: number): string { return mockUsers.value.find((item) => item.id === userId)?.displayName ?? `用户 #${userId}` }
function selectFile(event: Event): void { selectedFile.value = (event.target as HTMLInputElement).files?.[0] }
function selectOpinionImages(event: Event): void { opinionImages.value = Array.from((event.target as HTMLInputElement).files ?? []) }
async function checksum(file: File): Promise<string> { const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer()); return Array.from(new Uint8Array(digest)).map((value) => value.toString(16).padStart(2, '0')).join('') }
async function registerFile(): Promise<void> { if (!selectedFile.value || !fileKey.value.trim()) { setNotice('请选择文件并填写文件业务标识。'); return } try { const session = await reviewApi.createUploadSession(taskId.value, fileCategory.value); const saved = await reviewApi.registerFile(taskId.value, { uploadSessionId: session.uploadSessionId, category: fileCategory.value, businessFileKey: fileKey.value.trim(), fileName: selectedFile.value.name, fileSize: selectedFile.value.size, md5: await checksum(selectedFile.value) }); setNotice(`文件元数据已登记，版本 V${saved.versionNo}。`); selectedFile.value = undefined; fileKey.value = ''; if (fileInput.value) fileInput.value.value = '' } catch (cause) { setNotice(cause instanceof Error ? cause.message : '文件登记失败') } }
async function download(fileId: number): Promise<void> { try { const result = await reviewApi.requestDownload(fileId); window.open(result.downloadUrl, '_blank', 'noopener') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '下载失败') } }
function format(value?: string): string { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function statusForCheck(item: CheckItem): string { return item.result ?? 'PENDING' }
</script>

<template>
  <div v-if="loading" class="loading page-loading">正在加载任务详情…</div>
  <div v-else-if="error" class="api-error page-loading"><b>无法加载任务</b><span>{{ error }}</span><button class="btn primary" @click="load">重试</button></div>
  <template v-else-if="task">
    <div class="page-head detail-head"><div><div class="crumb">评审任务 / {{ task.projectName }}</div><h1>{{ task.taskName }}</h1></div><div><span class="tag">{{ task.reviewType === 'PCB' ? 'PCB布局布线评审' : '原理图评审' }}</span> <StatusTag :value="task.status" /></div></div>
    <section class="card task-summary"><div class="summary-grid"><div><span>项目</span><b>{{ task.projectName }}</b></div><div><span>评审类型</span><b>{{ task.reviewType === 'PCB' ? 'PCB布局布线评审' : '原理图评审' }}</b></div><div><span>设计者</span><b>{{ task.designerName }}</b></div><div><span>评审角色</span><b>{{ task.reviewRoles.join(' / ') }}</b></div></div><p class="flow-status">流程状态　<b><StatusTag :value="task.status" /></b></p><TaskFlow :status="task.status" :review-type="task.reviewType" /></section>
    <p v-if="notice" class="toast-message">{{ notice }}</p>
    <div class="detail-tabs"><button v-for="item in visibleStageTabs" :key="item[0]" :class="{ active: activeTab === item[0] }" @click="activeTab = item[0]">{{ item[1] }}</button></div>
    <section v-if="activeTab === 'overview'" class="detail-grid"><div class="card"><h2>任务信息</h2><div class="detail-list"><div><span>任务状态</span><StatusTag :value="task.status" /></div><div><span>设计名称</span><b>{{ task.designName }}</b></div><div><span>期望完成日期</span><b>{{ task.expectedCompletedDate }}</b></div><div><span>评审描述</span><b>{{ task.reviewDescription || '—' }}</b></div><div><span>意见总数</span><b>{{ opinions.length }}</b></div><div><span>互检项数量</span><b>{{ checkItems.length }}</b></div></div></div><div class="card"><h2>流程提示</h2><p class="notice">{{ statusText }}</p><p v-if="task.status !== 'FINISHED'" class="muted">当前任务乐观锁版本：{{ task.version }}。流程动作需满足当前节点权限后方可提交。</p><p v-else class="success-text">任务已结束，已可在“归档记录”查看冻结的数据。</p></div></section>
    <section v-else-if="activeTab === 'finish'" class="tab-panel finish-panel">
      <div class="section-head"><div><h2>结束确认</h2><p>确认所有阶段人员均已处理，系统将冻结任务、意见、文件和邮件归档。</p></div><StatusTag :value="task.status" /></div>
      <div class="card finish-checklist">
        <div class="finish-row"><b>互检单</b><span>{{ checkItems.length && checkItems.every((item) => item.status !== 'PENDING') ? '已完成' : '待处理' }}</span></div>
        <div class="finish-row"><b>设计者答复</b><span>{{ opinions.some((item) => item.status === 'PENDING_REPLY' || item.status === 'CONFIRMED_REJECTED') ? '待处理' : '已完成' }}</span></div>
        <div class="finish-row"><b>专家确认</b><span>{{ opinions.some((item) => item.status === 'PENDING_CONFIRMATION') ? '待处理' : '已完成' }}</span></div>
        <div class="finish-row"><b>{{ task.reviewType === 'SCHEMATIC' ? '原理图专家评审' : 'PCB 评审流程' }}</b><span>{{ task.status === 'PENDING_FINISH_CONFIRMATION' || task.status === 'FINISHED' ? '已完成' : '待处理' }}</span></div>
        <footer class="form-footer"><button class="btn primary" :disabled="task.status !== 'PENDING_FINISH_CONFIRMATION'" @click="finishTask">确认任务结束</button></footer>
      </div>
      <p v-if="task.status !== 'PENDING_FINISH_CONFIRMATION' && task.status !== 'FINISHED'" class="notice">当前尚未进入结束确认节点；待评审人员、意见闭环和阶段流转完成后方可结束。</p>
    </section>
    <section v-else-if="['opinions', 'designer-reply', 'process-review', 'structure-review', 'optional-reply', 'mutual-reply'].includes(activeTab)" class="tab-panel">
      <h2 class="workspace-title">{{ reviewWorkspaceTitle }}</h2>
      <BoardPreview v-if="!['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab)" :title="task.designName" :file-name="`${task.reviewType === 'PCB' ? 'PCB' : '原理图'} 评审画布`" />
      <div class="two-column">
        <div v-if="!['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab)" class="card">
          <div class="section-head"><div><h2>提交评审意见</h2><p>提交后由设计者答复，提出人确认。</p></div></div>
          <label>意见来源<select v-model="opinionForm.sourceType"><option value="EXPERT_REVIEW">专家评审</option><option value="PROCESS_REVIEW">工艺评审</option><option value="STRUCTURE_REVIEW">结构评审</option><option value="MUTUAL_EXTRA">互检额外意见</option></select></label>
          <label>具体评审意见<textarea v-model="opinionForm.content" rows="7" placeholder="请输入具体、可执行的评审意见" /></label>
          <label>问题图片（可多选）<input ref="opinionImageInput" type="file" accept="image/*" multiple @change="selectOpinionImages" /><small class="muted">{{ opinionImages.length ? `已选择 ${opinionImages.length} 张图片，提交时将自动上传并关联。` : '可上传 PCB 截图、标注图等作为意见依据。' }}</small></label>
          <button class="btn primary" @click="raiseOpinion">提交意见</button>
        </div>
        <div v-else class="card">
          <div class="section-head"><div><h2>意见看板</h2><p>请选择“接受”或“不接受”，逐条提交设计者答复。</p></div></div>
          <div class="stat-stack"><div><span>待答复意见</span><b>{{ opinions.filter((item) => item.status === 'PENDING_REPLY').length }}</b></div><div><span>待确认意见</span><b>{{ opinions.filter((item) => item.status === 'PENDING_CONFIRMATION').length }}</b></div><div><span>确认不通过</span><b>{{ opinions.filter((item) => item.status === 'CONFIRMED_REJECTED').length }}</b></div></div>
          <p class="notice">设计文件修订后，可在“上传工艺/结构图”阶段登记新版本；无需重新发起整轮评审。</p>
        </div>
        <div class="card"><h2>意见统计</h2><div class="stat-stack"><div><span>待答复</span><b>{{ opinions.filter((item) => item.status === 'PENDING_REPLY').length }}</b></div><div><span>待确认</span><b>{{ opinions.filter((item) => item.status === 'PENDING_CONFIRMATION').length }}</b></div><div><span>确认通过</span><b>{{ opinions.filter((item) => item.status === 'CONFIRMED_PASS').length }}</b></div></div></div>
      </div>
      <div v-if="opinions.some((item) => item.attachments.length)" class="card">
        <h2>意见图片</h2>
        <div v-for="opinion in opinions.filter((item) => item.attachments.length)" :key="`attachment-${opinion.id}`" class="reply-history">
          <b>意见 #{{ opinion.id }}：</b>
          <button v-for="attachment in opinion.attachments" :key="attachment.fileId" class="btn compact" @click="download(attachment.fileId)">{{ attachment.fileName }}</button>
        </div>
      </div>
      <div class="opinion-list"><article v-for="opinion in opinions" :key="opinion.id" class="card opinion-card"><div class="section-head"><div><span class="source-label">{{ opinion.sourceType }}</span><StatusTag :value="opinion.status" /><h3>{{ opinion.content }}</h3><p>提出人：用户 #{{ opinion.raisedBy }} · 意见 ID：{{ opinion.id }}</p></div></div><div v-if="opinion.designerReplies.length" class="reply-history"><b>设计者答复</b><div v-for="reply in opinion.designerReplies" :key="reply.id">第 {{ reply.replyNo }} 轮 · {{ reply.replyType }} · {{ reply.reason || '未填写补充说明' }} <small>{{ format(reply.repliedAt) }}</small></div></div><div v-if="task.designerId === identity.userId && opinion.status === 'PENDING_REPLY'" class="inline-form"><select v-model="draft(opinion).replyType"><option value="ACCEPT">接受并修改</option><option value="REJECT">不接受</option></select><input v-model="draft(opinion).reason" placeholder="答复说明" /><button class="btn primary" @click="replyOpinion(opinion)">提交答复</button></div><div v-if="opinion.raisedBy === identity.userId && opinion.status === 'PENDING_CONFIRMATION'" class="action-row"><button class="btn primary" @click="confirmOpinion(opinion, true)">确认通过</button><button class="btn danger" @click="confirmOpinion(opinion, false)">不通过</button></div></article><div v-if="!opinions.length" class="card empty">暂无评审意见</div></div></section>
    <section v-else-if="activeTab === 'check-items'" class="tab-panel"><MutualCheckForm :task-id="taskId" :items="checkItems" @submitted="load" /></section>
    <section v-else-if="activeTab === 'reviewers' || activeTab === 'hardware-reviewers'" class="tab-panel"><div class="section-head"><div><h2>{{ activeTab === 'hardware-reviewers' ? '硬件专家分配' : '互检单负责人分配' }}</h2><p>{{ activeTab === 'hardware-reviewers' ? '由硬件开发部经理选择一名或多名硬件专家进行原理图评审。' : '选择一名或多名互检负责人；多人须全部完成后，阶段才算完成。' }}</p></div><StatusTag value="PENDING" /></div><div class="card"><div class="reviewer-picker"><label v-for="user in mockUsers.filter((item) => !item.roles.includes('DESIGNER'))" :key="user.id" class="reviewer-option"><input v-model="selectedReviewerIds" type="checkbox" :value="user.id" /><span><b>{{ user.displayName }}</b><small>{{ user.roles.join(' / ') || user.departmentName }}</small></span></label></div><footer class="form-footer"><button class="btn" @click="saveReviewers(true)">改派</button><button class="btn primary" @click="saveReviewers(false)">{{ activeTab === 'hardware-reviewers' ? '分配专家并开始评审' : '分配并开启互检' }}</button></footer></div><div class="card"><h2>当前已分配人员</h2><table class="data-table"><thead><tr><th>负责人</th><th>职责</th><th>处理状态</th></tr></thead><tbody><tr v-for="reviewer in reviewers" :key="reviewer.reviewerId"><td>{{ userName(reviewer.reviewerId) }}</td><td>{{ reviewer.role }}</td><td><StatusTag :value="reviewer.status" /></td></tr><tr v-if="!reviewers.length"><td colspan="3" class="empty">暂未分配人员</td></tr></tbody></table></div></section>
    <section v-else-if="activeTab === 'files'" class="tab-panel"><div class="card"><h2>登记阶段文件</h2><p class="notice">本地 Mock 环境仅登记元数据；请选择文件后前端计算 SHA-256 并调用已有上传会话、文件登记接口。</p><div class="inline-form file-form"><select v-model="fileCategory"><option value="PCB_SCHEMATIC">PCB / 原理图文件</option><option value="PROCESS">工艺文件</option><option value="STRUCTURE">结构文件</option></select><input v-model="fileKey" placeholder="文件业务标识，例如 MAIN_PCB" /><input ref="fileInput" type="file" @change="selectFile" /><button class="btn primary" @click="registerFile">登记文件</button></div></div><div class="card"><h2>已归档阶段文件</h2><p v-if="!archive" class="muted">任务结束后，该区域将展示每条文件业务链的最新版本。</p><table v-else class="data-table"><thead><tr><th>阶段</th><th>文件名</th><th>版本</th><th>上传人</th><th>上传时间</th><th>操作</th></tr></thead><tbody><tr v-for="file in archive.stageFiles" :key="file.fileId"><td>{{ file.stageName }}</td><td>{{ file.fileName }}</td><td>V{{ file.versionNo }}</td><td>{{ file.uploaderName }}</td><td>{{ format(file.uploadedAt) }}</td><td><button class="btn compact" @click="download(file.fileId)">下载</button></td></tr></tbody></table></div></section>
    <section v-else class="tab-panel"><div v-if="!archive" class="card unsupported"><div class="unsupported-icon">⌁</div><h2>任务尚未结束</h2><p>任务完成时，后端会冻结流转意见、阶段文件与邮件记录。</p></div><template v-else><div class="card"><div class="section-head"><div><h2>一、流转意见</h2><p>已包含专家意见和设计者答复。</p></div></div><table class="data-table"><thead><tr><th>时间</th><th>阶段</th><th>操作人</th><th>流转意见</th></tr></thead><tbody><tr v-for="item in archive.flowOpinions" :key="`${item.source}-${item.sourceId}`"><td>{{ format(item.occurredAt) }}</td><td>{{ item.stageName }}</td><td>{{ item.operatorName }}</td><td>{{ item.content }}<div v-if="item.designerReplies.length" class="reply-history"><b>设计者答复：</b><div v-for="reply in item.designerReplies" :key="reply.replyNo">第 {{ reply.replyNo }} 轮：{{ reply.replyType }} · {{ reply.content || '—' }}</div></div></td></tr></tbody></table></div><div class="card"><h2>二、阶段文件</h2><table class="data-table"><thead><tr><th>阶段</th><th>文件名</th><th>上传人</th><th>上传时间</th><th>操作</th></tr></thead><tbody><tr v-for="file in archive.stageFiles" :key="file.fileId"><td>{{ file.stageName }}</td><td>{{ file.fileName }}</td><td>{{ file.uploaderName }}</td><td>{{ format(file.uploadedAt) }}</td><td><button class="btn compact" @click="download(file.fileId)">下载</button></td></tr></tbody></table></div><div class="card"><h2>三、邮件记录</h2><table class="data-table"><thead><tr><th>发送时间</th><th>场景</th><th>收件人</th><th>状态</th></tr></thead><tbody><tr v-for="mail in archive.mailRecords" :key="`${mail.sentAt}-${mail.recipient}`"><td>{{ format(mail.sentAt) }}</td><td>{{ mail.scenario }}</td><td>{{ mail.recipient }}</td><td><StatusTag :value="mail.deliveryStatus" /></td></tr><tr v-if="!archive.mailRecords.length"><td colspan="4" class="empty">暂无邮件投递记录</td></tr></tbody></table></div></template></section>
  </template>
</template>
