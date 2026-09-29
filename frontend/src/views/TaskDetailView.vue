<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import TaskFlow from '@/components/TaskFlow.vue'
import MutualCheckForm from '@/components/MutualCheckForm.vue'
import { reviewApi } from '@/api/review-api'
import { identity } from '@/stores/identity'
import type { Archive, AssignableReviewer, CheckItemCategory, CheckItemListItem, Opinion, OpinionSummary, Task } from '@/api/types'

const route = useRoute()
const taskId = computed(() => Number(route.params.taskId))
const activeTab = ref('overview')
const stageTabs = [
  ['overview', '任务信息'], ['opinions', '专家评审'], ['designer-reply', '设计者答复'],
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
const finishStageSummaries = ref<Record<string, OpinionSummary | undefined>>({})
const checkItemCategories = ref<CheckItemCategory[]>([])
const archive = ref<Archive>()
const assignableReviewers = ref<AssignableReviewer[]>([])
const whitelistPeople = ref<Array<{ id: number; employeeNo: string; displayName: string; email: string; departmentName: string; roles: string[] }>>([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const opinionForm = reactive({ severity: 'GENERAL' as Opinion['severity'] })
const opinionListFilters = reactive({ severity: '', status: '' })
const opinionPagination = reactive({ pageNo: 1, pageSize: 10, total: 0 })
const opinionEditor = ref<HTMLElement>()
const screenshotEditor = ref<HTMLElement>()
const opinionListSection = ref<HTMLElement>()
const replyDrafts = reactive<Record<number, { replyNo: number; replyType: 'ACCEPT' | 'REJECT'; reason: string }>>({})
const opinionImageIndexes = reactive<Record<number, number>>({})
const reviewerEmployeeNos = ref('')
const selectedReviewerEmployeeNos = ref<string[]>([])
const structureFileInput = ref<HTMLInputElement>()
const processFileInput = ref<HTMLInputElement>()
const designerWorkflowFileInput = ref<HTMLInputElement>()
const pcbReviewFileInput = ref<HTMLInputElement>()
const processReplyFileInput = ref<HTMLInputElement>()
const structureReplyFileInput = ref<HTMLInputElement>()
const finalPcbFileInput = ref<HTMLInputElement>()
const schematicFinalFileInput = ref<HTMLInputElement>()
const structureFile = ref<File>()
const processFile = ref<File>()
const designerWorkflowFile = ref<File>()
const pcbReviewFile = ref<File>()
const processReplyFile = ref<File>()
const structureReplyFile = ref<File>()
const finalPcbFile = ref<File>()
const schematicFinalFile = ref<File>()
const finalPcbFileCategory = ref<'PCB_REVIEW' | 'PCB_PROCESS_REVIEW' | 'PCB_STRUCTURE_REVIEW'>('PCB_REVIEW')
const pcbStageStartMessage = ref('')
const designerWorkflowMessage = ref('')
const pcbFirstReplyFileUploaded = reactive({ pcb: false, process: false, structure: false })
const editingOpinionId = ref<number>()
type OpinionEditDraft = { severity: Opinion['severity']; comment: string; screenshots: string }
const opinionEditDrafts = reactive<Record<number, OpinionEditDraft>>({})

const statusText = computed(() => task.value?.status === 'FINISHED' ? '归档已经冻结，所有内容只读。' : '当前详情根据后端任务状态实时加载。')
const visibleStageTabs = computed(() => task.value?.reviewType === 'SCHEMATIC' ? schematicStageTabs : stageTabs)
const isDesignerReplyPage = computed(() => ['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab.value))
const currentRoleCodes = computed(() => identity.roles.split(',').map((role) => role.trim()).filter(Boolean))
const isAdministrator = computed(() => currentRoleCodes.value.includes('HARDWARE_DEPARTMENT_MANAGER'))
const canActAsDesigner = computed(() => Boolean(task.value) && (task.value!.designerId === identity.userId || isAdministrator.value))
const canFinishTask = computed(() => Boolean(task.value) && (isAdministrator.value || (task.value!.reviewType === 'PCB'
  ? currentRoleCodes.value.includes('PCB_LEADER')
  : currentRoleCodes.value.includes('SCHEMATIC_LEADER'))))
const canSubmitNoOpinion = computed(() => task.value?.reviewType === 'PCB'
  && ['opinions', 'process-review', 'structure-review'].includes(activeTab.value))
const canUploadPcbFirstReplyFiles = computed(() => task.value?.reviewType === 'PCB'
  && activeTab.value === 'designer-reply'
  && task.value.status === 'PCB_EXPERT_REVIEWING'
  && canActAsDesigner.value)
const canUploadPcbReplyStageFiles = computed(() => task.value?.reviewType === 'PCB'
  && activeTab.value === 'optional-reply'
  && task.value.status === 'PCB_PROCESS_STRUCTURE_REVIEWING'
  && canActAsDesigner.value)
const canUploadPcbFinalFile = computed(() => task.value?.reviewType === 'PCB'
  && activeTab.value === 'mutual-reply'
  && task.value.status === 'MUTUAL_CHECK_REVIEWING'
  && canActAsDesigner.value)
const isSchematicFirstReplyPage = computed(() => task.value?.reviewType === 'SCHEMATIC'
  && activeTab.value === 'designer-reply')
const isSchematicFirstReplyNode = computed(() => isSchematicFirstReplyPage.value
  && task.value?.status === 'MUTUAL_CHECK_REVIEWING')
const canUploadSchematicFinalFile = computed(() => task.value?.reviewType === 'SCHEMATIC'
  && canActAsDesigner.value
  && (isSchematicFirstReplyNode.value
    || (activeTab.value === 'optional-reply' && task.value.status === 'SCHEMATIC_REVIEWING')))
const canStartPcbStageReviews = computed(() => Boolean(opinionSummary.value)
  && opinionSummary.value!.pendingReply === 0
  && opinionSummary.value!.pendingConfirmation === 0
  && opinionSummary.value!.confirmedRejected === 0
  && opinionSummary.value!.unsubmittedReviewers.length === 0)
type DesignerWorkflowStep = { action: string; label: string; comment: string; nextTab: string; fileCategory?: string; fileLabel?: string }
const designerWorkflowStep = computed<DesignerWorkflowStep | undefined>(() => {
  if (!task.value || !canActAsDesigner.value) return undefined
  if (task.value.reviewType === 'PCB' && activeTab.value === 'optional-reply' && task.value.status === 'PCB_PROCESS_STRUCTURE_REVIEWING') {
    return { action: 'START_PCB_MATUAL_ASSIGNMENT', label: '进入互检单分配', comment: '工艺、结构评审意见已闭环，开启互检单分配', nextTab: 'reviewers' }
  }
  if (task.value.reviewType === 'PCB' && activeTab.value === 'mutual-reply' && task.value.status === 'MUTUAL_CHECK_REVIEWING') {
    return { action: 'PREPARE_FINISH', label: '提交流程并准备结束', comment: '互检单意见已闭环，准备结束任务', nextTab: 'finish' }
  }
  if (task.value.reviewType === 'SCHEMATIC' && activeTab.value === 'designer-reply' && task.value.status === 'MUTUAL_CHECK_REVIEWING') {
    return { action: 'START_SCHEMATIC_EXPERT_ASSIGNMENT', label: '提交流程，进入专家分配', comment: '互检单意见已闭环，进入硬件专家分配', nextTab: 'hardware-reviewers' }
  }
  if (task.value.reviewType === 'SCHEMATIC' && activeTab.value === 'optional-reply' && task.value.status === 'SCHEMATIC_REVIEWING') {
    return { action: 'PREPARE_FINISH', label: '提交流程并准备结束', comment: '原理图评审意见已闭环，准备结束任务', nextTab: 'finish' }
  }
  return undefined
})
const isCombinedPcbFinalWorkflow = computed(() => canUploadPcbFinalFile.value
  && designerWorkflowStep.value?.action === 'PREPARE_FINISH')
const isCombinedSchematicFinalWorkflow = computed(() => canUploadSchematicFinalFile.value
  && designerWorkflowStep.value?.action === 'PREPARE_FINISH')
const isSchematicFirstReplyWorkflow = computed(() => isSchematicFirstReplyPage.value)
type FinishStage = { key: string; label: string; sourceTypes: string }
const finishStages = computed<FinishStage[]>(() => task.value?.reviewType === 'PCB'
  ? [
      { key: 'mutual', label: '互检单', sourceTypes: 'MUTUAL_CHECK_ITEM,MUTUAL_EXTRA' },
      { key: 'pcb', label: 'PCB评审流程', sourceTypes: 'EXPERT_REVIEW' },
      { key: 'process', label: '工艺评审', sourceTypes: 'PROCESS_REVIEW' },
      { key: 'structure', label: '结构评审', sourceTypes: 'STRUCTURE_REVIEW' }
    ]
  : [
      { key: 'mutual', label: '互检单', sourceTypes: 'MUTUAL_CHECK_ITEM,MUTUAL_EXTRA' },
      { key: 'schematic', label: '原理图评审', sourceTypes: 'SCHEMATIC_REVIEW' }
    ])
function finishStageStatus(stage: FinishStage): 'NC' | 'COMPLETED' | 'PENDING' | 'LOADING' {
  const summary = finishStageSummaries.value[stage.key]
  if (!summary) return 'LOADING'
  if (summary.total === 0) return 'NC'
  return summary.pendingReply === 0 && summary.pendingConfirmation === 0 && summary.confirmedRejected === 0
    ? 'COMPLETED'
    : 'PENDING'
}
function finishStageStatusText(stage: FinishStage): string {
  return ({ NC: 'NC', COMPLETED: '已完成', PENDING: '待处理', LOADING: '加载中' } as const)[finishStageStatus(stage)]
}
const canEndWorkflow = computed(() => finishStages.value.length > 0
  && finishStages.value.every((stage) => ['NC', 'COMPLETED'].includes(finishStageStatus(stage))))
const unfinishedFinishStages = computed(() => finishStages.value
  .filter((stage) => finishStageStatus(stage) === 'PENDING' || finishStageStatus(stage) === 'LOADING')
  .map((stage) => stage.label))
const canSubmitDesignerWorkflow = computed(() => Boolean(opinionSummary.value)
  && opinionSummary.value!.pendingReply === 0
  && opinionSummary.value!.pendingConfirmation === 0
  && opinionSummary.value!.confirmedRejected === 0)
const mutualOpinions = computed(() => opinions.value.filter((item) => item.sourceType === 'MUTUAL_CHECK_ITEM'))
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
    return activeTab.value === 'hardware-reviewers' ? 'START_SCHEMATIC_EXPERT_REVIEW' : 'START_SCHEMATIC_MATUAL_REVIEW'
  }
  return 'START_PCB_MATUAL_REVIEW'
})
async function load(): Promise<void> {
  loading.value = true; error.value = ''; notice.value = ''
  try {
    // 意见来源依赖任务类型；先取得任务，避免原理图首次加载误用 PCB 的 EXPERT_REVIEW。
    const taskData = await reviewApi.getTask(taskId.value)
    task.value = taskData
    const [opinionsData, summaryData, checkItemsData, whitelistPage] = await Promise.all([
      reviewApi.listOpinions(taskId.value, opinionFilters()),
      reviewApi.getOpinionSummary(taskId.value, opinionSourceTypes()),
      reviewApi.listCheckItems(taskId.value),
      reviewApi.listReviewerWhitelists({ pageNo: 1, pageSize: 1000 })
    ])
    const users = whitelistPage.items
    opinions.value = normalizeOpinions(opinionsData.items); opinionSummary.value = summaryData; opinionPagination.total = opinionsData.total; opinionPagination.pageNo = opinionsData.pageNo; checkItemCategories.value = checkItemsData; whitelistPeople.value = users.map((user) => ({ id: user.id, employeeNo: user.employeeNo, displayName: user.displayName || user.employeeNo, email: user.email || '', departmentName: user.departmentName || '', roles: [user.reviewRole] })); identity.userId = 1
    syncActiveTabForTaskStatus()
    await refreshFinishStageSummaries()
    await refreshPcbFirstReplyFileUploadStates()
    if (taskData.status === 'FINISHED') archive.value = await reviewApi.getArchive(taskId.value)
    await loadAssignableReviewers()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载任务详情失败' } finally { loading.value = false }
}
function opinionSourceTypes(): string | undefined {
  if (task.value?.reviewType === 'PCB' && activeTab.value === 'optional-reply') return 'PROCESS_REVIEW,STRUCTURE_REVIEW'
  if (task.value?.reviewType === 'SCHEMATIC' && activeTab.value === 'designer-reply') return 'MUTUAL_CHECK_ITEM,MUTUAL_EXTRA'
  if (task.value?.reviewType === 'SCHEMATIC' && activeTab.value === 'optional-reply') return 'SCHEMATIC_REVIEW'
  if (activeTab.value === 'mutual-reply') return 'MUTUAL_CHECK_ITEM,MUTUAL_EXTRA'
  return undefined
}
function opinionSourceType(): string | undefined {
  return undefined
}
function opinionFilters(): { severity?: string; status?: string; sourceType?: string; sourceTypes?: string; scene: 'REVIEW_WORKSPACE' | 'DESIGNER_REPLY'; pageNo: number; pageSize: number } {
  const filters = { severity: opinionListFilters.severity || undefined, status: opinionListFilters.status || undefined, pageNo: opinionPagination.pageNo, pageSize: opinionPagination.pageSize }
  if (['designer-reply', 'optional-reply', 'mutual-reply'].includes(activeTab.value)) return { ...filters, sourceType: opinionSourceType(), sourceTypes: opinionSourceTypes(), scene: 'DESIGNER_REPLY' }
  if (activeTab.value === 'process-review') return { ...filters, sourceType: 'PROCESS_REVIEW', scene: 'REVIEW_WORKSPACE' }
  if (activeTab.value === 'structure-review') return { ...filters, sourceType: 'STRUCTURE_REVIEW', scene: 'REVIEW_WORKSPACE' }
  return { ...filters, sourceType: task.value?.reviewType === 'SCHEMATIC' ? 'SCHEMATIC_REVIEW' : 'EXPERT_REVIEW', scene: 'REVIEW_WORKSPACE' }
}
function normalizeOpinions(items: Opinion[]): Opinion[] { return items.map((item) => ({ ...item, comment: item.richText || item.comment, replies: item.replies ?? [] })) }
function opinionImages(opinion: Opinion): string[] {
  const template = document.createElement('template')
  template.innerHTML = opinion.richText || opinion.comment || ''
  return Array.from(template.content.querySelectorAll('img')).map((image) => image.getAttribute('src') || '').filter(Boolean)
}
function opinionText(opinion: Opinion): string {
  const template = document.createElement('template')
  template.innerHTML = opinion.richText || opinion.comment || ''
  template.content.querySelectorAll('img').forEach((image) => image.remove())
  return template.innerHTML
}
function currentOpinionImage(opinion: Opinion): string {
  const images = opinionImages(opinion)
  if (!images.length) return ''
  const index = opinionImageIndexes[opinion.id] ?? 0
  const normalizedIndex = Math.min(Math.max(index, 0), images.length - 1)
  opinionImageIndexes[opinion.id] = normalizedIndex
  return images[normalizedIndex]
}
function moveOpinionImage(opinion: Opinion, direction: number): void {
  const images = opinionImages(opinion)
  if (images.length < 2) return
  const current = opinionImageIndexes[opinion.id] ?? 0
  opinionImageIndexes[opinion.id] = (current + direction + images.length) % images.length
}
async function refreshOpinions(): Promise<void> { try { const page = await reviewApi.listOpinions(taskId.value, opinionFilters()); opinions.value = normalizeOpinions(page.items); opinionPagination.total = page.total; opinionPagination.pageNo = page.pageNo } catch { /* 页面主数据已加载时不打断其他区域 */ } }
async function refreshOpinionSummary(): Promise<void> { try { opinionSummary.value = await reviewApi.getOpinionSummary(taskId.value, opinionSourceTypes()) } catch { /* 不影响意见列表操作 */ } }
async function refreshFinishStageSummaries(): Promise<void> {
  const stages = finishStages.value
  if (!stages.length) return
  try {
    const results = await Promise.all(stages.map(async (stage) => [stage.key, await reviewApi.getOpinionSummary(taskId.value, stage.sourceTypes)] as const))
    finishStageSummaries.value = Object.fromEntries(results)
  } catch {
    finishStageSummaries.value = {}
  }
}
function resetOpinionPage(): void { opinionPagination.pageNo = 1; void refreshOpinions() }
async function refreshCheckItems(): Promise<void> {
  try {
    checkItemCategories.value = await reviewApi.listCheckItems(taskId.value)
  } catch (cause) {
    setNotice(cause instanceof Error ? cause.message : '互检单加载失败，请重试。')
  }
}
async function refreshMutualCheckWorkspace(): Promise<void> {
  // 固定检查项保存后只刷新互检区域依赖的数据，避免整页 loading 卸载子组件，
  // 使“已保存”的反馈能够稳定保留在当前互检单页面。
  await Promise.all([
    refreshCheckItems(),
    refreshOpinions(),
    refreshOpinionSummary(),
    refreshFinishStageSummaries()
  ])
}
function syncActiveTabForTaskStatus(): void {
  if (task.value?.status === 'MUTUAL_CHECK_REVIEWING' && activeTab.value === 'reviewers') {
    activeTab.value = 'check-items'
  }
  if (task.value?.status === 'SCHEMATIC_REVIEWING' && activeTab.value === 'hardware-reviewers') {
    activeTab.value = 'opinions'
  }
}
function openStageTab(tab: string): void {
  if (task.value?.status === 'MUTUAL_CHECK_REVIEWING' && tab === 'reviewers') {
    activeTab.value = 'check-items'
    setNotice('互检人员已完成分配，已切换到互检单。')
    void refreshCheckItems()
    return
  }
  activeTab.value = tab
  if (tab === 'check-items') {
    void refreshCheckItems()
  }
}
function filterOpinionsByStatus(status: string): void {
  opinionListFilters.status = status
  resetOpinionPage()
  window.requestAnimationFrame(() => opinionListSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
function changeOpinionPage(pageNo: number): void { if (pageNo < 1 || pageNo > Math.ceil(opinionPagination.total / opinionPagination.pageSize)) return; opinionPagination.pageNo = pageNo; void refreshOpinions() }
async function loadAssignableReviewers(): Promise<void> {
  if (!task.value || !['reviewers', 'hardware-reviewers'].includes(activeTab.value)) {
    assignableReviewers.value = []
    return
  }
  try {
    assignableReviewers.value = await reviewApi.listAssignableReviewers(task.value.reviewType, task.value.status)
  } catch {
    assignableReviewers.value = []
  }
}
async function refreshPcbFirstReplyFileUploadStates(): Promise<void> {
  if (!task.value || task.value.reviewType !== 'PCB' || task.value.status !== 'PCB_EXPERT_REVIEWING') return
  try {
    const [pcbFiles, processFiles, structureFiles] = await Promise.all([
      reviewApi.latestFiles(taskId.value, 'PCB_REVIEW'),
      reviewApi.latestFiles(taskId.value, 'PCB_PROCESS_REVIEW'),
      reviewApi.latestFiles(taskId.value, 'PCB_STRUCTURE_REVIEW')
    ])
    pcbFirstReplyFileUploaded.pcb = pcbFiles.length > 0
    pcbFirstReplyFileUploaded.process = processFiles.length > 0
    pcbFirstReplyFileUploaded.structure = structureFiles.length > 0
  } catch {
    // 文件状态查询失败不影响编辑或流程推进；最终以流程接口的服务端校验为准。
  }
}
watch(activeTab, async () => { syncActiveTabForTaskStatus(); await loadAssignableReviewers(); await refreshPcbFirstReplyFileUploadStates() })
watch(activeTab, () => { opinionPagination.pageNo = 1; void refreshOpinions(); void refreshOpinionSummary() })
watch(() => task.value?.status, () => {
  // 分配已完成时，旧分配页不再有候选人可查；进入真正的评审工作区。
  syncActiveTabForTaskStatus()
})
onMounted(load)

function setNotice(value: string): void { notice.value = value; window.setTimeout(() => { if (notice.value === value) notice.value = '' }, 3500) }
function clearOpinionDraft(): void {
  opinionForm.severity = 'GENERAL'
  opinionEditor.value?.replaceChildren()
  screenshotEditor.value?.replaceChildren()
}
async function raiseOpinion(): Promise<void> {
  const opinionContent = opinionEditor.value?.innerHTML.trim() ?? ''
  const screenshots = screenshotEditor.value?.innerHTML.trim() ?? ''
  if (![opinionContent, screenshots].some((item) => item && item !== '<br>')) { setNotice('请填写具体评审意见或粘贴问题截图。'); return }
  const comment = `${opinionContent}${screenshots}`
  try {
    await reviewApi.raiseOpinion(taskId.value, { ...opinionForm, sourceType: currentOpinionSource(), comment, richText: comment })
    clearOpinionDraft()
    await refreshOpinions(); await refreshOpinionSummary(); setNotice('评审意见已提交。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '提交失败') }
}
function canManageOpinion(opinion: Opinion): boolean {
  return !isDesignerReplyPage.value && (opinion.raisedBy === identity.userId || isAdministrator.value) && opinion.status === 'PENDING_REPLY'
}
function imageHtml(content: string): string {
  const template = document.createElement('template')
  template.innerHTML = content
  return Array.from(template.content.querySelectorAll('img')).map((image) => image.outerHTML).join('')
}
function editDraft(opinion: Opinion): OpinionEditDraft {
  return opinionEditDrafts[opinion.id] ??= {
    severity: opinion.severity === 'PASS' ? 'GENERAL' : opinion.severity,
    comment: opinionText(opinion),
    screenshots: imageHtml(opinion.richText || opinion.comment || '')
  }
}
function editOpinion(opinion: Opinion): void {
  editingOpinionId.value = opinion.id
  editDraft(opinion)
}
function cancelOpinionEdit(): void {
  if (editingOpinionId.value) delete opinionEditDrafts[editingOpinionId.value]
  editingOpinionId.value = undefined
}
function updateEditContent(opinion: Opinion, event: Event): void { editDraft(opinion).comment = (event.currentTarget as HTMLElement).innerHTML }
function updateEditScreenshots(opinion: Opinion, event: Event): void { editDraft(opinion).screenshots = imageHtml((event.currentTarget as HTMLElement).innerHTML) }
function handleEditScreenshotPaste(opinion: Opinion, event: ClipboardEvent): void {
  const files = Array.from(event.clipboardData?.files ?? []).filter((file) => file.type.startsWith('image/'))
  if (!files.length) return
  event.preventDefault()
  const editor = event.currentTarget as HTMLElement
  files.forEach((file) => { const reader = new FileReader(); reader.onload = () => { editor.innerHTML += `<img src="${String(reader.result)}" alt="问题截图" />`; editDraft(opinion).screenshots = imageHtml(editor.innerHTML) }; reader.readAsDataURL(file) })
}
async function saveOpinionEdit(opinion: Opinion): Promise<void> {
  const draft = editDraft(opinion)
  const comment = `${draft.comment}${draft.screenshots}`
  if (!comment || comment === '<br>') { setNotice('请填写评审意见或问题截图。'); return }
  try {
    await reviewApi.updateOpinion(opinion.id, { severity: draft.severity, comment, richText: comment })
    cancelOpinionEdit()
    await refreshOpinions(); await refreshOpinionSummary(); setNotice('评审意见已修改。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '修改失败') }
}
async function deleteOpinion(opinion: Opinion): Promise<void> {
  if (!window.confirm('确认删除该评审意见吗？删除后不可恢复。')) return
  try {
    await reviewApi.deleteOpinion(opinion.id)
    await refreshOpinions(); await refreshOpinionSummary(); setNotice('评审意见已删除。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '删除失败') }
}
async function submitNoOpinion(): Promise<void> {
  if (!canSubmitNoOpinion.value || !window.confirm('确认当前阶段没有评审意见并提交吗？该操作不会推进流程。')) return
  try {
    await reviewApi.submitNoOpinion(taskId.value, currentNoOpinionSource())
    await refreshOpinions(); await refreshOpinionSummary(); setNotice('已确认无意见，不会推进流程。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '无意见确认提交失败') }
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
  return task.value?.reviewType === 'SCHEMATIC' ? 'SCHEMATIC_REVIEW' : 'EXPERT_REVIEW'
}
function currentNoOpinionSource(): 'EXPERT_REVIEW' | 'SCHEMATIC_REVIEW' | 'PROCESS_REVIEW' | 'STRUCTURE_REVIEW' {
  if (activeTab.value === 'process-review') return 'PROCESS_REVIEW'
  if (activeTab.value === 'structure-review') return 'STRUCTURE_REVIEW'
  return task.value?.reviewType === 'SCHEMATIC' ? 'SCHEMATIC_REVIEW' : 'EXPERT_REVIEW'
}
function draft(opinion: Opinion): { replyNo: number; replyType: 'ACCEPT' | 'REJECT'; reason: string } {
  const nextReplyNo = opinion.replies.length + 1
  const current = replyDrafts[opinion.id]
  if (!current || current.replyNo !== nextReplyNo) {
    replyDrafts[opinion.id] = { replyNo: nextReplyNo, replyType: 'ACCEPT', reason: '' }
  }
  return replyDrafts[opinion.id]
}
function requiresReply(opinion: Opinion): boolean { return opinion.status === 'PENDING_REPLY' || opinion.status === 'CONFIRMED_REJECTED' }
function latestConfirmation(opinion: Opinion) { return opinion.replies.at(-1)?.confirmation }
async function replyOpinion(opinion: Opinion): Promise<void> {
  const reply = draft(opinion)
  if (opinion.status === 'CONFIRMED_REJECTED' && !reply.reason.trim()) {
    setNotice('专家未确认通过时，请填写本次重新答复说明。')
    return
  }
  try { await reviewApi.replyOpinion(opinion.id, reply); await refreshOpinions(); await refreshOpinionSummary(); await refreshFinishStageSummaries(); setNotice('设计者答复已提交，等待提出人确认。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '答复失败') }
}
async function confirmOpinion(opinion: Opinion, passed: boolean): Promise<void> { try { await reviewApi.confirmOpinion(opinion.id, { passed }); await refreshOpinions(); await refreshOpinionSummary(); await refreshFinishStageSummaries(); setNotice(passed ? '已确认通过。' : '已退回设计者重新答复。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '确认失败') } }
function requireFinishStagesComplete(): boolean {
  if (canEndWorkflow.value) return true
  setNotice(`暂不能结束流程：${unfinishedFinishStages.value.join('、') || '评审状态'}仍为待处理，请先完成并确认通过对应意见。`)
  return false
}
async function finishTask(): Promise<void> { if (!requireFinishStagesComplete()) return; try { await reviewApi.transition(taskId.value, { actions: ['FINISH'], comment: '在结束确认页确认任务结束' }); await load(); setNotice('任务已结束，归档记录已冻结。') } catch (cause) { setNotice(cause instanceof Error ? cause.message : '任务结束失败') } }
async function startPcbStageReviews(): Promise<void> {
  await reviewApi.transition(taskId.value, { actions: ['START_PCB_STRUCTURE_REVIEW', 'START_PCB_PROCESS_REVIEW'], comment: '工艺图、结构图已上传，同时开启工艺和结构评审' })
  await load()
  activeTab.value = 'process-review'
  setNotice('已开启工艺和结构评审。')
}
async function startPcbMutualAssignment(): Promise<void> {
  try {
    await reviewApi.transition(taskId.value, { actions: ['START_PCB_MATUAL_ASSIGNMENT'], comment: '前序评审意见已闭环，开启互检单分配' })
    await load(); activeTab.value = 'reviewers'; setNotice('已进入互检单分配，请选择互检人员。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '暂不能开启互检单分配，请确认前序意见均已通过。') }
}
async function startSchematicExpertAssignment(): Promise<void> {
  try {
    await reviewApi.transition(taskId.value, { actions: ['START_SCHEMATIC_EXPERT_ASSIGNMENT'], comment: '互检单意见已闭环，开启硬件专家分配' })
    await load(); activeTab.value = 'hardware-reviewers'; setNotice('已进入硬件专家分配，请选择评审专家。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '暂不能开启硬件专家分配，请确认互检单意见均已通过。') }
}
async function prepareFinish(): Promise<void> {
  if (!requireFinishStagesComplete()) return
  try {
    await reviewApi.transition(taskId.value, { actions: ['PREPARE_FINISH'], comment: '前序评审已完成，准备结束任务' })
    await load(); setNotice('已进入结束确认节点。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '暂不能准备结束，请确认当前流程已完成。') }
}
async function saveReviewers(): Promise<void> {
  const assignmentTab = activeTab.value
  const employeeNos = selectedReviewerEmployeeNos.value.length ? selectedReviewerEmployeeNos.value : reviewerEmployeeNos.value.split(',').map((value) => value.trim()).filter(Boolean)
  if (!employeeNos.length) {
    setNotice(`请至少选择一名${assignmentTab === 'hardware-reviewers' ? '硬件专家' : '互检负责人'}。`)
    return
  }
  try {
    await reviewApi.transition(taskId.value, {
      actions: [reviewerAssignmentAction.value],
      reviewerEmployeeNos: employeeNos,
      comment: assignmentTab === 'hardware-reviewers' ? '已分配硬件专家并开始原理图评审' : '已分配互检负责人并开启互检'
    })
    await load()
    selectedReviewerEmployeeNos.value = []
    reviewerEmployeeNos.value = ''
    // 分配动作已把任务推进到评审节点；继续停留在分配页会按新状态查询候选人，因而必然为空。
    activeTab.value = assignmentTab === 'hardware-reviewers' ? 'opinions' : 'check-items'
    setNotice(assignmentTab === 'hardware-reviewers' ? '硬件专家已分配，已进入原理图评审。' : '互检负责人已分配，已进入互检单评审。')
  } catch (cause) {
    setNotice(cause instanceof Error ? cause.message : '分配或流程推进失败')
  }
}
function userName(userId: number): string { return whitelistPeople.value.find((item) => item.id === userId)?.displayName ?? `白名单人员 #${userId}` }
function reviewerRoleLabel(role: string): string {
  return ({
    HARDWARE_EXPERT: '硬件评审',
    EMC_EXPERT: 'EMC评审',
    PCB_EXPERT: 'PCB评审',
    PROCESS_EXPERT: '工艺评审',
    STRUCTURE_EXPERT: '结构评审',
    PCB_MUTUAL_CHECK: 'PCB互检单评审',
    SCHEMATIC_HARDWARE_EXPERT: '原理图硬件评审',
    SCHEMATIC_OTHER_EXPERT: '原理图其他评审',
    SCHEMATIC_MUTUAL_CHECK: '原理图互检单评审',
    SCHEMATIC_LEADER: '原理图组长'
  } as Record<string, string>)[role] || role
}
function selectPcbStageFile(kind: 'PROCESS' | 'STRUCTURE', event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (kind === 'PROCESS') {
    processFile.value = file
    pcbFirstReplyFileUploaded.process = false
  } else {
    structureFile.value = file
    pcbFirstReplyFileUploaded.structure = false
  }
}
function selectPcbReviewFile(event: Event): void {
  pcbReviewFile.value = (event.target as HTMLInputElement).files?.[0]
  pcbFirstReplyFileUploaded.pcb = false
}
function selectPcbReplyStageFile(kind: 'PROCESS' | 'STRUCTURE', event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (kind === 'PROCESS') processReplyFile.value = file
  else structureReplyFile.value = file
}
function selectFinalPcbFile(event: Event): void { finalPcbFile.value = (event.target as HTMLInputElement).files?.[0] }
function selectSchematicFinalFile(event: Event): void { schematicFinalFile.value = (event.target as HTMLInputElement).files?.[0] }
function selectDesignerWorkflowFile(event: Event): void { designerWorkflowFile.value = (event.target as HTMLInputElement).files?.[0] }
async function uploadPcbReviewFile(): Promise<void> {
  if (!pcbReviewFile.value) { setNotice('请选择需要重新上传的 PCB 评审文件。'); return }
  try {
    await reviewApi.uploadTaskFile(taskId.value, 'PCB_REVIEW', pcbReviewFile.value)
    pcbReviewFile.value = undefined
    if (pcbReviewFileInput.value) pcbReviewFileInput.value.value = ''
    pcbFirstReplyFileUploaded.pcb = true
    setNotice('最新 PCB 任务文件已上传，专家可下载该文件进行评审。')
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : 'PCB 任务文件上传失败') }
}
async function uploadPcbFirstReplyStageFile(kind: 'PROCESS' | 'STRUCTURE'): Promise<void> {
  const file = kind === 'PROCESS' ? processFile.value : structureFile.value
  if (!file) { setNotice(`请选择需要上传的${kind === 'PROCESS' ? '工艺' : '结构'}文件。`); return }
  const category = kind === 'PROCESS' ? 'PCB_PROCESS_REVIEW' : 'PCB_STRUCTURE_REVIEW'
  try {
    await reviewApi.uploadTaskFile(taskId.value, category, file)
    if (kind === 'PROCESS') {
      processFile.value = undefined
      if (processFileInput.value) processFileInput.value.value = ''
      pcbFirstReplyFileUploaded.process = true
    } else {
      structureFile.value = undefined
      if (structureFileInput.value) structureFileInput.value.value = ''
      pcbFirstReplyFileUploaded.structure = true
    }
    setNotice(`${kind === 'PROCESS' ? '工艺' : '结构'}文件已上传，可在三个文件均准备完成后开启评审。`)
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : `${kind === 'PROCESS' ? '工艺' : '结构'}文件上传失败`) }
}
async function uploadPcbReplyStageFile(kind: 'PROCESS' | 'STRUCTURE'): Promise<void> {
  const file = kind === 'PROCESS' ? processReplyFile.value : structureReplyFile.value
  if (!file) { setNotice(`请选择需要重新上传的${kind === 'PROCESS' ? '工艺' : '结构'}文件。`); return }
  const category = kind === 'PROCESS' ? 'PCB_PROCESS_REVIEW' : 'PCB_STRUCTURE_REVIEW'
  try {
    await reviewApi.uploadTaskFile(taskId.value, category, file)
    if (kind === 'PROCESS') {
      processReplyFile.value = undefined
      if (processReplyFileInput.value) processReplyFileInput.value.value = ''
    } else {
      structureReplyFile.value = undefined
      if (structureReplyFileInput.value) structureReplyFileInput.value.value = ''
    }
    setNotice(`最新${kind === 'PROCESS' ? '工艺' : '结构'}文件已上传。`)
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : `${kind === 'PROCESS' ? '工艺' : '结构'}文件上传失败`) }
}
async function uploadFinalPcbFile(): Promise<void> {
  if (!finalPcbFile.value) { setNotice('请选择最终文件。'); return }
  try {
    await reviewApi.uploadTaskFile(taskId.value, finalPcbFileCategory.value, finalPcbFile.value)
    finalPcbFile.value = undefined
    if (finalPcbFileInput.value) finalPcbFileInput.value.value = ''
    designerWorkflowMessage.value = '最终文件已上传；确认全部互检意见通过后可提交准备结束。'
    setNotice('最终文件已上传。')
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '最终文件上传失败'
    designerWorkflowMessage.value = message
    setNotice(message)
  }
}
async function uploadSchematicFinalFile(): Promise<void> {
  if (!canActAsDesigner.value) { setNotice('只有任务设计者可以上传原理图文件。'); return }
  if (!schematicFinalFile.value) { setNotice('请选择需要重新上传的最终原理图文件。'); return }
  try {
    await reviewApi.uploadTaskFile(taskId.value, 'SCHEMATIC_REVIEW', schematicFinalFile.value)
    schematicFinalFile.value = undefined
    if (schematicFinalFileInput.value) schematicFinalFileInput.value.value = ''
    designerWorkflowMessage.value = '最新原理图文件已上传，可提交进入硬件专家分配。'
    setNotice('最终原理图文件已上传。')
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '最终原理图文件上传失败'
    designerWorkflowMessage.value = message
    setNotice(message)
  }
}
async function uploadAndStartOptionalReview(): Promise<void> {
  if (!canStartPcbStageReviews.value) {
    const message = '暂不能开启：请先确认专家评审意见全部通过，且所有已分配专家均已提交意见或确认无意见。'
    pcbStageStartMessage.value = message
    setNotice(message)
    return
  }
  pcbStageStartMessage.value = '正在校验已上传的工艺、结构文件并开启评审…'
  try {
    // 此按钮只推进流程。工艺、结构文件须由各自的上传按钮先完成落库，
    // 然后统一通过 WorkflowApplicationService#transition 的批量动作校验和推进。
    await startPcbStageReviews()
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '流程推进失败，请确认工艺和结构文件均已上传。'
    pcbStageStartMessage.value = message
    setNotice(message)
  }
}
async function submitDesignerWorkflow(): Promise<void> {
  const step = designerWorkflowStep.value
  if (!step) return
  designerWorkflowMessage.value = ''
  if (!canSubmitDesignerWorkflow.value) {
    const message = '暂不能提交：请先完成并确认通过当前页面范围内的全部意见。互检阶段还需确认固定检查项和额外意见。'
    designerWorkflowMessage.value = message
    setNotice(message)
    return
  }
  if (step.fileCategory && !designerWorkflowFile.value) {
    const message = `请选择${step.fileLabel?.replace(' *', '') || '所需文件'}。`
    designerWorkflowMessage.value = message
    setNotice(message)
    return
  }
  try {
    if (step.fileCategory && designerWorkflowFile.value) await reviewApi.uploadTaskFile(taskId.value, step.fileCategory, designerWorkflowFile.value)
    await reviewApi.transition(taskId.value, { actions: [step.action], comment: step.comment })
    designerWorkflowFile.value = undefined
    if (designerWorkflowFileInput.value) designerWorkflowFileInput.value.value = ''
    await load(); activeTab.value = step.nextTab; setNotice('流程已提交并进入下一节点。')
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '流程提交失败，请确认意见已全部通过且已上传所需文件。'
    designerWorkflowMessage.value = message
    setNotice(message)
  }
}
async function download(fileId: number): Promise<void> { try { const blob = await reviewApi.downloadContent(fileId); const url = URL.createObjectURL(blob); const popup = window.open(url, '_blank', 'noopener'); if (!popup) { const anchor = document.createElement('a'); anchor.href = url; anchor.download = ''; anchor.click() }; window.setTimeout(() => URL.revokeObjectURL(url), 30_000) } catch (cause) { setNotice(cause instanceof Error ? cause.message : '下载失败') } }
async function exportArchiveOpinions(): Promise<void> { try { const blob = await reviewApi.exportArchiveOpinions(taskId.value); const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${task.value?.projectName ?? '评审任务'}_评审意见.xlsx`; anchor.click(); window.setTimeout(() => URL.revokeObjectURL(url), 30_000) } catch (cause) { setNotice(cause instanceof Error ? cause.message : '导出评审意见失败') } }
async function downloadLatestReviewFile(): Promise<void> {
  const category = activeTab.value === 'process-review' ? 'PCB_PROCESS_REVIEW' : activeTab.value === 'structure-review' ? 'PCB_STRUCTURE_REVIEW' : task.value?.reviewType === 'PCB' ? 'PCB_REVIEW' : 'SCHEMATIC_REVIEW'
  try {
    const file = (await reviewApi.latestFiles(taskId.value, category))[0]
    if (!file) { setNotice('暂未找到当前评审阶段的文件。'); return }
    await download(file.id)
  } catch (cause) { setNotice(cause instanceof Error ? cause.message : '下载失败') }
}
function format(value?: string): string { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function severityLabel(severity: Opinion['severity']): string { return ({ SERIOUS: '严重', GENERAL: '一般', MINOR: '轻微', PASS: '无问题' } as Record<Opinion['severity'], string>)[severity] }
function statusForCheck(item: CheckItemListItem): string { return item.opinion?.result ?? 'PENDING' }
</script>

<template>
  <div v-if="loading" class="loading page-loading">正在加载任务详情…</div>
  <div v-else-if="error" class="api-error page-loading"><b>无法加载任务</b><span>{{ error }}</span><button class="btn primary" @click="load">重试</button></div>
  <template v-else-if="task">
    <div class="page-head detail-head"><div><div class="crumb">评审任务 / {{ task.projectName }}</div><h1>{{ task.taskName }}</h1></div><div><span class="tag">{{ task.reviewType === 'PCB' ? 'PCB布局布线评审' : '原理图评审' }}</span> <StatusTag :value="task.status" /></div></div>
    <section class="card task-summary"><div class="summary-grid"><div><span>项目</span><b>{{ task.projectName }}</b></div><div><span>评审类型</span><b>{{ task.reviewType === 'PCB' ? 'PCB布局布线评审' : '原理图评审' }}</b></div><div><span>设计者</span><b>{{ task.designerName }}</b></div><div><span>评审角色</span><b>{{ task.reviewRoles.map(reviewerRoleLabel).join(' / ') }}</b></div></div><p class="flow-status">流程状态　<b><StatusTag :value="task.status" /></b></p><TaskFlow :status="task.status" :review-type="task.reviewType" /></section>
    <p v-if="notice" class="toast-message">{{ notice }}</p>
    <div class="detail-tabs"><button v-for="item in visibleStageTabs" :key="item[0]" :class="{ active: activeTab === item[0] }" @click="openStageTab(item[0])">{{ item[1] }}</button></div>
    <section v-if="activeTab === 'overview'" class="detail-grid"><div class="card"><div class="section-head"><h2>任务信息</h2><RouterLink v-if="task.status === 'DRAFT' && canActAsDesigner" :to="`/tasks/${task.id}/edit`" class="btn">编辑任务</RouterLink></div><div class="detail-list"><div><span>任务状态</span><StatusTag :value="task.status" /></div><div><span>设计名称</span><b>{{ task.designName }}</b></div><div><span>期望完成日期</span><b>{{ task.expectedCompletedDate }}</b></div><div><span>评审描述</span><b>{{ task.reviewDescription || '—' }}</b></div><div><span>意见总数</span><b>{{ opinions.length }}</b></div><div><span>互检项数量</span><b>{{ checkItems.length }}</b></div></div></div><div class="card"><h2>流程提示</h2><p class="notice">{{ statusText }}</p><p v-if="task.status !== 'FINISHED'" class="muted">流程动作需满足当前节点权限后方可提交。</p><p v-else class="success-text">任务已结束，已可在“归档记录”查看冻结的数据。</p></div><div class="card flow-record-card"><h2>流转记录</h2><table class="data-table"><thead><tr><th>操作时间</th><th>操作人</th><th>操作动作</th><th>说明</th></tr></thead><tbody><tr v-for="record in task.flowRecords || []" :key="record.id"><td>{{ format(record.operatedAt) }}</td><td>{{ record.operatorName }}</td><td>{{ record.actionName }}</td><td>{{ record.comment || '—' }}</td></tr><tr v-if="!(task.flowRecords || []).length"><td colspan="4" class="empty">暂无流转记录</td></tr></tbody></table></div></section>
    <section v-else-if="activeTab === 'finish'" class="tab-panel finish-panel">
      <div class="section-head"><div><h2>结束确认</h2><p>确认所有阶段人员均已处理，系统将冻结任务、意见、文件和邮件归档。</p></div><StatusTag :value="task.status" /></div>
      <div class="card finish-checklist">
        <div v-for="stage in finishStages" :key="stage.key" class="finish-row"><b>{{ stage.label }}</b><span :class="`finish-status ${finishStageStatus(stage).toLowerCase()}`">{{ finishStageStatusText(stage) }}</span></div>
        <footer class="form-footer"><button v-if="task.status !== 'FINISHED' && canActAsDesigner" class="btn" @click="prepareFinish">准备结束</button><button v-if="task.status !== 'FINISHED' && canFinishTask" class="btn primary" @click="finishTask">确认任务结束</button></footer>
      </div>
      <p v-if="task.status !== 'FINISHED' && !canEndWorkflow" class="notice">暂不能结束流程：{{ unfinishedFinishStages.join('、') || '评审状态' }}仍为待处理，请先完成并确认通过对应意见。</p>
      <p v-else-if="task.status !== 'FINISHED'" class="success-text">所有适用评审步骤均已完成（NC 表示本流程未产生该步骤意见），可以结束流程。</p>
    </section>
    <section v-else-if="['opinions', 'designer-reply', 'process-review', 'structure-review', 'optional-reply', 'mutual-reply'].includes(activeTab)" class="tab-panel" :class="{ 'designer-reply-workspace': isDesignerReplyPage }">
      <div class="workspace-heading"><h2 class="workspace-title">{{ reviewWorkspaceTitle }}</h2></div>
      <section v-if="!isDesignerReplyPage" class="card review-file-card">
        <div><span>设计者最新上传文件</span><b>{{ task.designName }}</b><small>当前评审文件 · {{ task.reviewType === 'PCB' ? 'PCB 布局布线' : '原理图' }}</small></div>
        <div class="review-file-actions"><button v-if="canSubmitNoOpinion" type="button" class="btn" @click="submitNoOpinion">无意见，确认提交</button><button type="button" class="btn primary review-file-download" @click="downloadLatestReviewFile">↓ 下载最新版本文件</button></div>
      </section>
      <div class="two-column">
        <div v-if="!isDesignerReplyPage" class="card review-submit-card">
          <div class="review-submit-grid"><div class="screenshot-panel"><h2>问题截图</h2><div ref="screenshotEditor" class="screenshot-paste" contenteditable="true" data-placeholder="Ctrl + V 粘贴问题截图" @paste="handleScreenshotPaste" /></div><div class="review-opinion-panel"><header><h2>评审意见</h2><div class="action-row"><button class="btn primary" @click="raiseOpinion">提交评审意见</button></div></header><div class="review-content-field"><label>问题等级 *<select v-model="opinionForm.severity"><option value="SERIOUS">严重</option><option value="GENERAL">一般</option><option value="MINOR">轻微</option></select></label><label>具体评审意见 *<div ref="opinionEditor" class="rich-opinion-editor" contenteditable="true" data-placeholder="请输入具体、可执行的评审意见" /></label></div></div></div>
        </div>
        <div v-else class="card designer-opinion-board"><div class="section-head"><div><h2>意见看板 <span class="board-help">?</span></h2></div></div><div class="designer-stat-grid"><button type="button" class="reply-stat pending-reply" :class="{ active: opinionListFilters.status === 'PENDING_REPLY' }" @click="filterOpinionsByStatus('PENDING_REPLY')"><span>待答复意见</span><b>{{ opinionSummary?.pendingReply ?? 0 }}</b></button><button type="button" class="reply-stat pending-confirm" :class="{ active: opinionListFilters.status === 'PENDING_CONFIRMATION' }" @click="filterOpinionsByStatus('PENDING_CONFIRMATION')"><span>待确认意见</span><b>{{ opinionSummary?.pendingConfirmation ?? 0 }}</b></button><button type="button" class="reply-stat rejected-stat" :class="{ active: opinionListFilters.status === 'CONFIRMED_REJECTED' }" @click="filterOpinionsByStatus('CONFIRMED_REJECTED')"><span>确认不通过</span><b>{{ opinionSummary?.confirmedRejected ?? 0 }}</b></button><button type="button" class="reply-stat confirmed-stat" :class="{ active: opinionListFilters.status === 'CONFIRMED_PASS' }" @click="filterOpinionsByStatus('CONFIRMED_PASS')"><span>确认通过</span><b>{{ opinionSummary?.confirmedPass ?? 0 }}</b></button></div><div class="designer-insights"><span>设计者 {{ (opinionSummary?.pendingReply ?? 0) + (opinionSummary?.confirmedRejected ?? 0) }} 条未答复意见</span><span>{{ opinionSummary?.pendingConfirmation ?? 0 }} 条待专家确认</span><span v-if="opinionSummary?.confirmedRejected">{{ opinionSummary.confirmedRejected }} 条意见需重新答复</span><span v-for="reviewer in opinionSummary?.unsubmittedReviewers ?? []" :key="`${reviewer.reviewRole}-${reviewer.reviewerId}`" class="unsubmitted-reviewer">{{ reviewer.reviewerName }}未提交意见</span></div></div>
      </div>
      <section v-if="canUploadPcbFirstReplyFiles" class="card pcb-first-reply-file-upload">
        <div class="section-head"><div><h2>上传评审文件</h2><p>首次设计者答复可在同一模块内分别更新 PCB、工艺和结构文件；工艺、结构文件上传完成后可开启下一阶段评审。</p></div></div>
        <div class="first-reply-upload-grid">
          <label class="stage-file-choice"><span class="file-type-tag">PCB</span><b>PCB 评审文件</b><small>上传时固定传 <code>PCB_REVIEW</code>，供专家下载评审。</small><input ref="pcbReviewFileInput" type="file" @change="selectPcbReviewFile" /><em :class="{ uploaded: pcbFirstReplyFileUploaded.pcb, pending: pcbReviewFile }">{{ pcbReviewFile ? `待上传：${pcbReviewFile.name}` : pcbFirstReplyFileUploaded.pcb ? '已上传' : '未选择任何文件' }}</em><button type="button" class="btn primary stage-file-upload-button" :disabled="!pcbReviewFile" @click.prevent="uploadPcbReviewFile">上传 PCB 文件</button></label>
          <label class="stage-file-choice"><span class="file-type-tag">工艺</span><b>工艺图文件</b><small>上传时固定传 <code>PCB_PROCESS_REVIEW</code>。</small><input ref="processFileInput" type="file" accept=".zip,.rar,.7z" @change="selectPcbStageFile('PROCESS', $event)" /><em :class="{ uploaded: pcbFirstReplyFileUploaded.process, pending: processFile }">{{ processFile ? `待上传：${processFile.name}` : pcbFirstReplyFileUploaded.process ? '已上传' : '未选择任何文件' }}</em><button type="button" class="btn primary stage-file-upload-button" :disabled="!processFile" @click.prevent="uploadPcbFirstReplyStageFile('PROCESS')">上传工艺文件</button></label>
          <label class="stage-file-choice"><span class="file-type-tag">结构</span><b>结构图文件</b><small>上传时固定传 <code>PCB_STRUCTURE_REVIEW</code>。</small><input ref="structureFileInput" type="file" accept=".zip,.rar,.7z" @change="selectPcbStageFile('STRUCTURE', $event)" /><em :class="{ uploaded: pcbFirstReplyFileUploaded.structure, pending: structureFile }">{{ structureFile ? `待上传：${structureFile.name}` : pcbFirstReplyFileUploaded.structure ? '已上传' : '未选择任何文件' }}</em><button type="button" class="btn primary stage-file-upload-button" :disabled="!structureFile" @click.prevent="uploadPcbFirstReplyStageFile('STRUCTURE')">上传结构文件</button></label>
        </div>
        <footer class="form-footer"><p v-if="pcbStageStartMessage" class="stage-start-message">{{ pcbStageStartMessage }}</p><button class="btn primary" @click="uploadAndStartOptionalReview">上传完成，开启工艺/结构评审</button></footer>
      </section>
      <section v-if="canUploadPcbReplyStageFiles" class="card pcb-reply-stage-upload">
        <div class="section-head"><div><h2>更新工艺/结构文件</h2><p>工艺或结构评审修改完成后，可分别上传当前最新文件；两个文件均独立登记，不会自动推进流程。</p></div></div>
        <div class="stage-upload-grid">
          <label class="stage-file-choice"><span class="file-type-tag">工艺</span><b>工艺文件</b><small>上传时固定传 <code>PCB_PROCESS_REVIEW</code>。</small><input ref="processReplyFileInput" type="file" @change="selectPcbReplyStageFile('PROCESS', $event)" /><em>{{ processReplyFile?.name || '未选择任何文件' }}</em><button type="button" class="btn primary stage-file-upload-button" :disabled="!processReplyFile" @click.prevent="uploadPcbReplyStageFile('PROCESS')">上传工艺文件</button></label>
          <label class="stage-file-choice"><span class="file-type-tag">结构</span><b>结构文件</b><small>上传时固定传 <code>PCB_STRUCTURE_REVIEW</code>。</small><input ref="structureReplyFileInput" type="file" @change="selectPcbReplyStageFile('STRUCTURE', $event)" /><em>{{ structureReplyFile?.name || '未选择任何文件' }}</em><button type="button" class="btn primary stage-file-upload-button" :disabled="!structureReplyFile" @click.prevent="uploadPcbReplyStageFile('STRUCTURE')">上传结构文件</button></label>
        </div>
      </section>
      <section v-if="isCombinedPcbFinalWorkflow" class="card pcb-final-file-upload">
        <div class="section-head"><div><h2>上传最终文件并准备结束</h2><p>可选上传最终交付文件；固定检查项和额外意见均确认通过后，在本模块提交流程并进入结束确认。</p></div></div>
        <div class="final-file-controls"><label>文件类型<select v-model="finalPcbFileCategory"><option value="PCB_REVIEW">PCB 评审文件</option><option value="PCB_PROCESS_REVIEW">工艺文件</option><option value="PCB_STRUCTURE_REVIEW">结构文件</option></select></label><label>最终文件<input ref="finalPcbFileInput" type="file" @change="selectFinalPcbFile" /><span>{{ finalPcbFile?.name || '未选择任何文件' }}</span></label></div>
        <footer class="form-footer"><p v-if="designerWorkflowMessage" class="stage-start-message">{{ designerWorkflowMessage }}</p><div class="final-workflow-actions"><button class="btn" :disabled="!finalPcbFile" @click="uploadFinalPcbFile">↑ 上传最终文件</button><button class="btn primary" @click="submitDesignerWorkflow">提交流程并准备结束</button></div></footer>
      </section>
      <section v-if="isSchematicFirstReplyPage || isCombinedSchematicFinalWorkflow" class="card schematic-final-file-upload">
        <div class="section-head"><div><h2>{{ isSchematicFirstReplyWorkflow ? '上传原理图文件并提交流程' : '上传最终原理图文件并准备结束' }}</h2><p>{{ isSchematicFirstReplyWorkflow ? '上传最新原理图文件后，提交进入硬件专家分配。' : '先上传最新原理图文件，再提交流程进入结束确认；上传本身不会推进流程。' }}</p></div></div>
        <label class="designer-transition-file"><b>最终原理图文件</b><input ref="schematicFinalFileInput" type="file" @change="selectSchematicFinalFile" /><span>{{ schematicFinalFile?.name || '未选择任何文件' }}</span></label>
        <footer class="form-footer"><p v-if="designerWorkflowMessage" class="stage-start-message">{{ designerWorkflowMessage }}</p><p v-if="isSchematicFirstReplyWorkflow && !isSchematicFirstReplyNode" class="stage-start-message">当前任务不在互检单评审节点，文件上传和流程提交已不可操作。</p><p v-else-if="!canActAsDesigner" class="stage-start-message">当前用户不是任务设计者，不能上传文件或提交流程。</p><div class="final-workflow-actions"><button class="btn" :disabled="!schematicFinalFile || !canActAsDesigner || (isSchematicFirstReplyWorkflow && !isSchematicFirstReplyNode)" @click="uploadSchematicFinalFile">↑ 上传原理图文件</button><button class="btn primary" :disabled="!canActAsDesigner || (isSchematicFirstReplyWorkflow && !isSchematicFirstReplyNode)" @click="submitDesignerWorkflow">{{ isSchematicFirstReplyWorkflow ? '提交流程' : '提交流程并准备结束' }}</button></div></footer>
      </section>
      <section v-if="designerWorkflowStep && !isCombinedPcbFinalWorkflow && !isCombinedSchematicFinalWorkflow && !isSchematicFirstReplyWorkflow" class="card designer-transition-card">
        <div class="section-head"><div><h2>流程推进</h2><p>当前阶段意见全部确认通过后，提交进入下一流程节点。</p></div></div>
        <label v-if="designerWorkflowStep.fileCategory" class="designer-transition-file"><b>{{ designerWorkflowStep.fileLabel }}</b><input ref="designerWorkflowFileInput" type="file" @change="selectDesignerWorkflowFile" /><span>{{ designerWorkflowFile?.name || '未选择任何文件' }}</span></label>
        <footer class="form-footer"><p v-if="designerWorkflowMessage" class="stage-start-message">{{ designerWorkflowMessage }}</p><button class="btn primary" :disabled="Boolean(designerWorkflowStep.fileCategory && !designerWorkflowFile)" @click="submitDesignerWorkflow">{{ designerWorkflowStep.label }}</button></footer>
      </section>
      <section ref="opinionListSection" class="card opinion-section">
        <header class="opinion-list-head"><h2>意见列表</h2><div class="opinion-filter"><select v-model="opinionListFilters.severity" @change="resetOpinionPage"><option value="">全部问题等级</option><option value="SERIOUS">严重</option><option value="GENERAL">一般</option><option value="MINOR">轻微</option></select><select v-model="opinionListFilters.status" @change="resetOpinionPage"><option value="">全部状态</option><option value="PENDING_REPLY">待答复</option><option value="PENDING_CONFIRMATION">待确认</option><option value="CONFIRMED_PASS">确认通过</option><option value="CONFIRMED_REJECTED">确认不通过</option></select><span class="tag">{{ opinionPagination.total }} 条</span></div></header>
        <div class="opinion-list compact-opinion-list">
          <article v-for="opinion in opinions" :key="opinion.id" class="opinion-row" :class="{ 'retry-required': opinion.status === 'CONFIRMED_REJECTED', editing: editingOpinionId === opinion.id }">
            <div class="opinion-main">
              <div class="opinion-meta"><span class="severity-chip" :class="opinion.severity.toLowerCase()">{{ severityLabel(opinion.severity) }}</span><StatusTag :value="opinion.status" /><span>提出人：{{ opinion.raisedByName || userName(opinion.raisedBy) }}</span><span>提出时间：{{ format(opinion.createdAt) }}</span></div>
              <div v-if="opinionText(opinion)" class="rich-opinion-content" v-html="opinionText(opinion)" />
              <div v-if="opinion.replies.length" class="reply-history"><b>设计者答复历史</b><div v-for="reply in opinion.replies" :key="reply.id" class="reply-history-row"><div>第 {{ reply.replyNo }} 轮：{{ reply.replyType === 'ACCEPT' ? '接受' : '不接受' }}｜{{ reply.reason || '未填写补充说明' }} <small>{{ format(reply.repliedAt) }}</small></div><div v-if="reply.confirmation" class="confirmation-history" :class="reply.confirmation.passed ? 'passed' : 'rejected'">专家{{ reply.confirmation.passed ? '确认通过' : '确认不通过' }}：{{ reply.confirmation.comment || '未填写确认意见' }} <small>{{ format(reply.confirmation.confirmedAt) }}</small></div><div v-else class="confirmation-history pending">等待专家确认</div></div></div>
              <div v-if="opinion.status === 'CONFIRMED_REJECTED'" class="retry-feedback"><b>专家未确认通过，请重新答复</b><span>退回原因：{{ latestConfirmation(opinion)?.comment || '专家未填写退回说明' }}</span></div>
              <section v-if="editingOpinionId === opinion.id" class="inline-opinion-editor">
                <header><b>编辑评审意见</b><button type="button" class="btn compact" @click="cancelOpinionEdit">取消</button></header>
                <div class="inline-opinion-edit-grid"><label>问题等级<select v-model="editDraft(opinion).severity"><option value="SERIOUS">严重</option><option value="GENERAL">一般</option><option value="MINOR">轻微</option></select></label><label>评审意见<div class="rich-opinion-editor" contenteditable="true" :innerHTML="editDraft(opinion).comment" data-placeholder="请输入具体、可执行的评审意见" @input="updateEditContent(opinion, $event)" /></label><label class="inline-edit-screenshots">问题截图<div class="screenshot-paste" contenteditable="true" :innerHTML="editDraft(opinion).screenshots" data-placeholder="Ctrl + V 粘贴问题截图" @input="updateEditScreenshots(opinion, $event)" @paste="handleEditScreenshotPaste(opinion, $event)" /></label></div>
                <footer class="form-footer"><button type="button" class="btn primary" @click="saveOpinionEdit(opinion)">保存修改</button></footer>
              </section>
              <div v-if="isDesignerReplyPage && canActAsDesigner && requiresReply(opinion)" class="inline-form reply-editor"><select v-model="draft(opinion).replyType"><option value="ACCEPT">接受并修改</option><option value="REJECT">不接受</option></select><textarea v-model="draft(opinion).reason" rows="3" :placeholder="opinion.status === 'CONFIRMED_REJECTED' ? '请填写本次重新答复说明（必填）' : '请填写答复说明（可选）'" /><button class="btn primary reply-submit" @click="replyOpinion(opinion)">{{ opinion.status === 'CONFIRMED_REJECTED' ? '重新提交答复' : '提交答复' }}</button></div>
              <div v-if="canManageOpinion(opinion)" class="action-row"><button class="btn compact" @click="editOpinion(opinion)">编辑</button><button class="btn compact danger" @click="deleteOpinion(opinion)">删除</button></div>
              <div v-if="!isDesignerReplyPage && opinion.raisedBy === identity.userId && opinion.status === 'PENDING_CONFIRMATION'" class="action-row"><button class="btn compact" @click="confirmOpinion(opinion, true)">通过</button><button class="btn compact danger" @click="confirmOpinion(opinion, false)">不通过</button></div>
            </div>
            <div v-if="opinionImages(opinion).length && editingOpinionId !== opinion.id" class="opinion-image-carousel">
              <button v-if="opinionImages(opinion).length > 1" type="button" class="carousel-control previous" aria-label="查看上一张问题截图" @click="moveOpinionImage(opinion, -1)">‹</button>
              <img :src="currentOpinionImage(opinion)" alt="问题截图" />
              <button v-if="opinionImages(opinion).length > 1" type="button" class="carousel-control next" aria-label="查看下一张问题截图" @click="moveOpinionImage(opinion, 1)">›</button>
              <span v-if="opinionImages(opinion).length > 1" class="carousel-count">{{ (opinionImageIndexes[opinion.id] ?? 0) + 1 }} / {{ opinionImages(opinion).length }}</span>
            </div>
          </article>
          <div v-if="!opinions.length" class="empty">暂无符合筛选条件的评审意见</div>
        </div>
        <footer v-if="opinionPagination.total > opinionPagination.pageSize" class="opinion-pagination"><button class="btn compact" :disabled="opinionPagination.pageNo <= 1" @click="changeOpinionPage(opinionPagination.pageNo - 1)">上一页</button><span>第 {{ opinionPagination.pageNo }} / {{ Math.ceil(opinionPagination.total / opinionPagination.pageSize) }} 页</span><button class="btn compact" :disabled="opinionPagination.pageNo >= Math.ceil(opinionPagination.total / opinionPagination.pageSize)" @click="changeOpinionPage(opinionPagination.pageNo + 1)">下一页</button></footer>
      </section>
    </section>
    <section v-else-if="activeTab === 'check-items'" class="tab-panel"><MutualCheckForm :task-id="taskId" :categories="checkItemCategories" @submitted="refreshMutualCheckWorkspace" /></section>
    <section v-else-if="activeTab === 'reviewers' && task.reviewType === 'PCB' && ['PCB_EXPERT_REVIEWING', 'PCB_PROCESS_STRUCTURE_REVIEWING'].includes(task.status)" class="tab-panel"><div class="section-head"><div><h2>互检单负责人分配</h2><p>前序专家、工艺和结构评审意见全部通过后，先开启互检单分配。</p></div><StatusTag :value="task.status" /></div><div class="card"><footer class="form-footer"><button class="btn primary" @click="startPcbMutualAssignment">开启互检单分配</button></footer></div></section>
    <section v-else-if="activeTab === 'hardware-reviewers' && task.reviewType === 'SCHEMATIC' && task.status === 'MUTUAL_CHECK_REVIEWING'" class="tab-panel"><div class="section-head"><div><h2>硬件专家分配</h2><p>互检单意见全部通过后，先开启硬件专家分配。</p></div><StatusTag :value="task.status" /></div><div class="card"><footer class="form-footer"><button class="btn primary" @click="startSchematicExpertAssignment">开启硬件专家分配</button></footer></div></section>
    <section v-else-if="activeTab === 'reviewers' || activeTab === 'hardware-reviewers'" class="tab-panel"><div class="section-head"><div><h2>{{ activeTab === 'hardware-reviewers' ? '硬件专家分配' : '互检单负责人分配' }}</h2><p>{{ activeTab === 'hardware-reviewers' ? '由硬件开发部经理选择一名或多名硬件专家进行原理图评审。' : '选择一名或多名互检负责人；多人须全部完成后，阶段才算完成。' }}</p></div><StatusTag :value="task.status" /></div><div class="card"><div class="reviewer-picker"><label v-for="user in assignableReviewers" :key="user.employeeNo" class="reviewer-option"><input v-model="selectedReviewerEmployeeNos" type="checkbox" :value="user.employeeNo" /><span><b>{{ user.displayName }}</b><small>{{ user.employeeNo }} · {{ user.departmentName }}</small></span></label><div v-if="!assignableReviewers.length" class="empty">当前节点暂无已配置且可用的白名单人员</div></div><footer class="form-footer"><button class="btn primary" :disabled="!assignableReviewers.length" @click="saveReviewers()">{{ activeTab === 'hardware-reviewers' ? '分配专家并开始评审' : '分配并开启互检' }}</button></footer></div></section>
    <section v-else class="tab-panel"><div v-if="!archive" class="card unsupported"><div class="unsupported-icon">⌁</div><h2>任务尚未结束</h2><p>任务完成时，系统将冻结流程节点和阶段文件。</p></div><template v-else><div class="card"><div class="section-head"><div><h2>一、流程节点</h2><p>仅展示节点时间、节点名称、操作人姓名和流转意见，不包含评审意见。</p></div><button class="btn primary" @click="exportArchiveOpinions">导出评审意见 Excel</button></div><table class="data-table"><thead><tr><th>节点时间</th><th>节点名称</th><th>操作人姓名</th><th>流转意见</th></tr></thead><tbody><tr v-for="(item, index) in archive.flowNodes" :key="`${item.occurredAt}-${item.stageName}-${index}`"><td>{{ format(item.occurredAt) }}</td><td>{{ item.stageName }}</td><td>{{ item.operatorName }}</td><td>{{ item.content }}</td></tr><tr v-if="!archive.flowNodes.length"><td colspan="4" class="empty">暂无流程节点</td></tr></tbody></table></div><div class="card"><div class="section-head"><div><h2>二、阶段文件</h2><p>展示归档时冻结的各阶段文件记录。</p></div></div><table class="data-table"><thead><tr><th>流程节点</th><th>文件名称</th><th>格式</th><th>大小</th><th>上传人</th><th>上传时间</th><th>操作</th></tr></thead><tbody><tr v-for="file in archive.stageFiles" :key="file.fileId"><td>{{ file.stageName }}</td><td>{{ file.fileName }}</td><td>{{ file.fileFormat || '-' }}</td><td>{{ file.fileSize == null ? '-' : file.fileSize + ' B' }}</td><td>{{ file.uploaderName }}</td><td>{{ format(file.uploadedAt) }}</td><td><button class="btn mini primary" @click="download(file.fileId)">下载</button></td></tr><tr v-if="!archive.stageFiles.length"><td colspan="7" class="empty">暂无阶段文件</td></tr></tbody></table></div><div class="card"><div class="section-head"><div><h2>三、邮件记录</h2><p>展示归档时冻结的邮件投递结果。</p></div></div><table class="data-table"><thead><tr><th>事件</th><th>收件人</th><th>邮件模板</th><th>投递状态</th><th>失败原因</th><th>尝试时间</th></tr></thead><tbody><tr v-for="(mail, index) in archive.notificationRecords" :key="`${mail.recipient}-${mail.attemptedAt}-${index}`"><td>{{ mail.eventType }}</td><td>{{ mail.recipient }}</td><td>{{ mail.templateCode }}</td><td>{{ mail.deliveryStatus }}</td><td>{{ mail.failureReason || '—' }}</td><td>{{ format(mail.attemptedAt) }}</td></tr><tr v-if="!archive.notificationRecords.length"><td colspan="6" class="empty">暂无邮件记录</td></tr></tbody></table></div></template></section>
  </template>
</template>

<style scoped>
.workspace-title{margin:0 0 10px;font-size:16px}
.workspace-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;min-height:34px}
.opinion-pagination{display:flex;justify-content:flex-end;align-items:center;gap:10px;padding-top:16px;color:#72778b;font-size:13px}
.review-file-card{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:20px;margin-bottom:0}
.review-file-card span,.review-file-card small{display:block;color:#878b9c;font-size:12px}
.review-file-card b{display:block;margin:6px 0;color:#24283c;font-size:16px}
.review-file-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
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
.screenshot-paste :deep(img){display:block;max-width:100%;max-height:260px;margin:8px 0;border-radius:5px;object-fit:contain}
.retry-required{border-color:#ffcbc8;background:linear-gradient(90deg,#fff 0%,#fff8f7 100%)}
.retry-feedback{display:grid;gap:5px;margin:12px 0;padding:10px 12px;border-left:3px solid #ef5350;border-radius:4px;background:#fff2f1;color:#a52d2a;font-size:13px}
.retry-feedback span{color:#76524f}.reply-editor{margin-top:14px}.retry-notice{color:#b6403f;background:#fff2f1;border-color:#ffd3d0}.rejected-stat{border-color:#ffcbc8!important;background:#fff8f7}.rejected-stat b{color:#e34a45!important}
.designer-opinion-board{grid-column:1/-1;padding:18px}.designer-opinion-board .section-head{margin-bottom:18px}.board-help{display:inline-grid;place-items:center;width:17px;height:17px;margin-left:4px;border:1px solid #8c8fb0;border-radius:50%;color:#6259bc;font-size:11px;font-weight:500;vertical-align:1px}.designer-stat-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;padding-bottom:10px;border-bottom:1px solid #ecebf1}.reply-stat{display:flex;align-items:center;justify-content:space-between;min-height:76px;padding:14px 12px;border:1px solid #dfe2ec;border-radius:10px;background:#fff;color:#4f5571;font-size:14px;text-align:left;cursor:pointer}.reply-stat:hover,.reply-stat.active{border-color:#7565ea;background:#f7f5ff;box-shadow:0 3px 10px rgba(102,87,217,.12)}.reply-stat:focus-visible{outline:2px solid #7565ea;outline-offset:2px}.reply-stat b{font-size:24px;line-height:1;color:#5a51bd}.pending-reply b,.rejected-stat b{color:#e14650!important}.pending-confirm b{color:#ca7900}.designer-insights{display:flex;flex-wrap:wrap;gap:10px;margin-top:10px}.designer-insights span{min-width:240px;padding:10px 11px;border:1px solid #e4e2f1;border-radius:8px;background:#faf9ff;color:#4d5269;font-size:13px;font-weight:600}
.designer-reply-workspace .opinion-row:not(.editing){grid-template-columns:minmax(0,1fr) minmax(240px,360px)}.designer-reply-workspace .opinion-image-carousel{grid-column:2}.designer-reply-workspace .reply-editor{grid-template-columns:160px minmax(0,1fr) auto;max-width:100%;align-items:start}.designer-reply-workspace .reply-editor textarea{min-height:76px;resize:vertical}.designer-reply-workspace .reply-submit{align-self:start;min-width:108px;height:38px}.opinion-main::after{display:block;clear:both;content:''}
.designer-stage-upload{margin-top:16px;padding:18px}.stage-file-heading{margin:0 0 16px}.stage-file-heading h2{margin:0;font-size:16px}.stage-file-heading p{margin:6px 0 0;color:#73798e;font-size:13px}.stage-upload-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.stage-file-choice{position:relative;display:grid;gap:10px;min-height:132px;padding:18px;border:1px solid #dce2ed;border-radius:11px;background:#fbfcff}.stage-file-choice b{font-size:15px;color:#24283c}.stage-file-choice small{color:#73798e;font-size:13px}.stage-file-choice input{width:auto!important;max-width:100%;padding:0!important;border:0!important;border-radius:0!important;background:transparent!important;box-shadow:none!important}.stage-file-choice em{position:absolute;right:18px;bottom:19px;max-width:56%;overflow:hidden;color:#696f82;font-size:12px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.file-type-tag{position:absolute;right:16px;top:17px;padding:4px 8px;border-radius:14px;background:#edf5ff;color:#2878d7;font-size:11px}.stage-upload-card .form-footer{margin-top:16px;padding-top:16px}.stage-file-history{display:none}
.finish-checklist{display:grid;gap:10px}.finish-row{display:flex;align-items:center;justify-content:space-between;min-height:52px;padding:0 14px;border:1px solid #dfe3ed;border-radius:8px;background:#fff}.finish-status{padding:5px 9px;border-radius:13px;font-size:12px;font-weight:700}.finish-status.completed{background:#e9f8ef;color:#278355}.finish-status.pending{background:#fff5e8;color:#bd7620}.finish-status.nc{background:#f0efff;color:#6759cf}.finish-status.loading{background:#f2f3f7;color:#74798b}.finish-checklist .form-footer{margin-top:8px}
.pcb-first-reply-file-upload{display:grid;gap:16px;margin-top:16px;padding:18px}.pcb-first-reply-file-upload .section-head{margin:0}.pcb-first-reply-file-upload .section-head h2{margin:0;font-size:16px}.pcb-first-reply-file-upload .section-head p{margin:6px 0 0;color:#73798e;font-size:13px}.pcb-first-reply-file-upload code{font-size:11px}.pcb-first-reply-file-upload .stage-file-choice{padding-bottom:58px}.pcb-first-reply-file-upload .stage-file-choice em{right:18px;bottom:54px;max-width:calc(100% - 36px)}.pcb-first-reply-file-upload .stage-file-choice em.pending{color:#bd6b12}.pcb-first-reply-file-upload .stage-file-choice em.uploaded{color:#258653;font-weight:700}.pcb-first-reply-file-upload .form-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:0;padding-top:16px}.first-reply-upload-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.stage-start-message{margin:0;color:#a14c26;font-size:13px;line-height:1.5}
.pcb-reply-stage-upload{display:grid;gap:16px;margin-top:16px;padding:18px}.pcb-reply-stage-upload .section-head{margin:0}.pcb-reply-stage-upload .section-head h2{margin:0;font-size:16px}.pcb-reply-stage-upload .section-head p{margin:6px 0 0;color:#73798e;font-size:13px}.pcb-reply-stage-upload code{font-size:11px}.pcb-reply-stage-upload .stage-file-choice{padding-bottom:58px}.stage-file-upload-button{position:absolute;right:18px;bottom:14px}
.pcb-final-file-upload{display:grid;gap:14px;margin-top:16px;padding:18px}.pcb-final-file-upload .section-head{margin:0}.pcb-final-file-upload .section-head h2{margin:0;font-size:16px}.pcb-final-file-upload .section-head p{margin:6px 0 0;color:#73798e;font-size:13px}.pcb-final-file-upload .form-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:0;padding-top:0}.final-workflow-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:10px}.final-file-controls{display:grid;grid-template-columns:minmax(180px,.45fr) minmax(280px,1fr);gap:14px}.final-file-controls label{display:grid;gap:7px;color:#525a70;font-size:13px;font-weight:700}.final-file-controls select{padding:9px;border:1px solid #dce1ec;border-radius:8px;background:#fff}.final-file-controls input{width:auto!important;max-width:100%;padding:0!important;border:0!important;border-radius:0!important;background:transparent!important;box-shadow:none!important}.final-file-controls span{color:#73798e;font-size:13px;font-weight:400}
.schematic-final-file-upload{display:grid;gap:14px;margin-top:16px;padding:18px}.schematic-final-file-upload .section-head{margin:0}.schematic-final-file-upload .section-head h2{margin:0;font-size:16px}.schematic-final-file-upload .section-head p{margin:6px 0 0;color:#73798e;font-size:13px}.schematic-final-file-upload .form-footer{margin-top:0;padding-top:0}
.designer-transition-card{margin-top:16px;padding:18px}.designer-transition-card .section-head{margin-bottom:14px}.designer-transition-card .section-head h2{margin:0;font-size:16px}.designer-transition-card .section-head p{margin:6px 0 0;color:#73798e;font-size:13px}.designer-transition-file{display:flex;align-items:center;flex-wrap:wrap;gap:10px;padding:13px 14px;border:1px solid #dce2ed;border-radius:9px;background:#fbfcff}.designer-transition-file b{color:#24283c;font-size:14px}.designer-transition-file input{width:auto!important;max-width:100%;padding:0!important;border:0!important;border-radius:0!important;background:transparent!important;box-shadow:none!important}.designer-transition-file span{color:#73798e;font-size:13px}
.opinion-section{padding:0}.opinion-list-head{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:18px 20px;border-bottom:1px solid #ebeaf0}.opinion-list-head h2{margin:0;font-size:16px}.opinion-filter{display:flex;align-items:center;gap:9px}.opinion-filter select{padding:7px 9px;border:1px solid #dfe1e9;border-radius:7px;background:#fff;color:#4e5366}.compact-opinion-list{gap:0}.opinion-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(240px,360px);gap:20px;padding:18px 20px;border-bottom:1px solid #ececf2}.opinion-row.editing{grid-template-columns:1fr}.opinion-row:last-child{border-bottom:0}.rich-opinion-content{min-height:30px;margin:10px 0 0;color:#25283a;font-size:14px;font-weight:600;line-height:1.7}.opinion-image-carousel{position:relative;display:grid;place-items:center;width:100%;height:210px;margin:0;border:1px solid #dce1ed;border-radius:9px;background:#f8f9fc;overflow:hidden}.opinion-image-carousel img{width:100%;height:100%;object-fit:contain}.carousel-control{position:absolute;z-index:1;top:calc(50% - 17px);display:grid;place-items:center;width:34px;height:34px;border:0;border-radius:50%;background:rgba(28,31,46,.68);color:#fff;font-size:26px;line-height:1}.carousel-control.previous{left:10px}.carousel-control.next{right:10px}.carousel-count{position:absolute;right:10px;bottom:9px;padding:3px 8px;border-radius:12px;background:rgba(28,31,46,.7);color:#fff;font-size:12px}.inline-opinion-editor{display:grid;gap:14px;margin-top:14px;padding:14px;border:1px solid #d9d5fa;border-radius:10px;background:#fbfaff}.inline-opinion-editor header{display:flex;align-items:center;justify-content:space-between}.inline-opinion-edit-grid{display:grid;grid-template-columns:140px minmax(0,1fr) minmax(240px,360px);gap:12px}.inline-opinion-edit-grid label{display:grid;align-content:start;gap:7px;color:#525a70;font-size:13px;font-weight:700}.inline-opinion-edit-grid select{padding:9px;border:1px solid #dce1ec;border-radius:8px;background:#fff}.inline-opinion-edit-grid .rich-opinion-editor{min-height:170px}.inline-opinion-edit-grid .screenshot-paste{min-height:170px;margin:0}.inline-opinion-editor .form-footer{margin:0;padding-top:0}.opinion-meta{display:flex;align-items:center;flex-wrap:wrap;gap:7px;color:#84889a;font-size:12px}.severity-chip{display:inline-flex;align-items:center;padding:3px 8px;border-radius:14px;background:#eaf3ff;color:#2874dc;font-size:12px}.severity-chip.serious{background:#fff0f0;color:#e14545}.severity-chip.minor{background:#f3f3f7;color:#73798a}.reply-history-row{margin-top:8px;padding-top:8px;border-top:1px solid #e6e3f5}.reply-history-row:first-of-type{margin-top:5px}.confirmation-history{margin-top:5px;padding:4px 8px;border-radius:5px;font-size:12px}.confirmation-history.passed{background:#ebf8ef;color:#278355}.confirmation-history.rejected{background:#fff0ef;color:#c64a44}.confirmation-history.pending{background:#f2f3f7;color:#74798b}
@media(max-width:820px){.workspace-heading{align-items:flex-start;flex-direction:column}.review-file-card{align-items:flex-start;flex-direction:column}.review-submit-grid,.designer-stat-grid,.stage-upload-grid,.first-reply-upload-grid,.final-file-controls,.opinion-row,.inline-opinion-edit-grid{grid-template-columns:1fr}.screenshot-paste{min-height:210px}.opinion-list-head{align-items:flex-start;flex-direction:column}.opinion-filter{width:100%;flex-wrap:wrap}.opinion-filter select{flex:1;min-width:130px}.designer-insights span{min-width:0;width:100%}.opinion-image-carousel{width:100%;height:230px}.designer-reply-workspace .reply-submit{width:100%}}
</style>
