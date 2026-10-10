<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { reviewApi } from '@/api/review-api'
import { OpinionSeverity, OpinionSource } from '@/api/types'
import type { CheckItemCategory, CheckItemListItem, Opinion } from '@/api/types'

const props = defineProps<{ taskId: number; categories: CheckItemCategory[] }>()
const emit = defineEmits<{ submitted: [] }>()
type EditValue = { result?: 'PASS' | 'FAIL' | 'NC'; comment: string; richText: string }
type ItemFeedback = { text: string; success?: boolean }
const edits = reactive<Record<number, EditValue>>({})
const itemFeedbacks = reactive<Record<number, ItemFeedback | undefined>>({})
const persistedItemIds = new Set<number>()
const message = ref('')
const saving = ref(false)
const savingItemId = ref<number>()
const extraOpinionMessage = ref('')
const extraOpinionSaving = ref(false)
const extraOpinionForm = reactive({ severity: OpinionSeverity.GENERAL })
const extraOpinionEditor = ref<HTMLElement>()
const extraOpinionScreenshotEditor = ref<HTMLElement>()
const extraOpinions = ref<Opinion[]>([])
const extraOpinionsLoading = ref(false)
const extraOpinionPagination = reactive({ pageNo: 1, pageSize: 20, total: 0 })
const checkItems = computed<CheckItemListItem[]>(() => props.categories.flatMap((category) => category.items))

function edit(item: CheckItemListItem): EditValue { return edits[item.id] }
function clearItemFeedback(itemId: number): void { delete itemFeedbacks[itemId] }
function validateItem(item: CheckItemListItem): string | undefined {
  const value = edit(item)
  if (!value.result) return '请选择该检查项的结果。'
  if (value.result !== 'PASS' && !value.comment.trim()) return '不合格或 NC 时请填写具体意见。'
  return undefined
}
function resultLabel(result: EditValue['result']): string { return result === 'PASS' ? '合格' : result === 'FAIL' ? '不合格' : result === 'NC' ? 'NC' : '未选择' }
function screenshotHtml(item: CheckItemListItem): string {
  const template = document.createElement('template')
  template.innerHTML = edit(item).richText
  return Array.from(template.content.querySelectorAll('img')).map((image) => image.outerHTML).join('')
}
function updateScreenshots(item: CheckItemListItem, editor: HTMLElement): void {
  const template = document.createElement('template')
  template.innerHTML = editor.innerHTML
  edit(item).richText = Array.from(template.content.querySelectorAll('img')).map((image) => image.outerHTML).join('')
}
function handleScreenshotInput(item: CheckItemListItem, event: Event): void {
  updateScreenshots(item, event.currentTarget as HTMLElement)
}
function handleOpinionPaste(item: CheckItemListItem, event: ClipboardEvent): void {
  const images = Array.from(event.clipboardData?.files ?? []).filter((file) => file.type.startsWith('image/'))
  if (!images.length) return
  event.preventDefault()
  const editor = event.currentTarget as HTMLElement
  images.forEach((image) => {
    const reader = new FileReader()
    reader.onload = () => { editor.innerHTML += `<img src="${String(reader.result)}" alt="问题截图" />`; updateScreenshots(item, editor) }
    reader.readAsDataURL(image)
  })
}
function handleExtraOpinionScreenshotPaste(event: ClipboardEvent): void {
  const images = Array.from(event.clipboardData?.files ?? []).filter((file) => file.type.startsWith('image/'))
  if (!images.length) return
  event.preventDefault()
  images.forEach((image) => {
    const reader = new FileReader()
    reader.onload = () => {
      if (extraOpinionScreenshotEditor.value) extraOpinionScreenshotEditor.value.innerHTML += `<img src="${String(reader.result)}" alt="问题截图" />`
    }
    reader.readAsDataURL(image)
  })
}
function severityLabel(severity: Opinion['severity']): string {
  return ({ [OpinionSeverity.SERIOUS]: '严重', [OpinionSeverity.GENERAL]: '一般', [OpinionSeverity.MINOR]: '轻微' })[severity]
}
function opinionStatusLabel(status: Opinion['status']): string {
  return ({ PENDING_REPLY: '待答复', PENDING_CONFIRMATION: '待确认', CONFIRMED_PASS: '确认通过', CONFIRMED_REJECTED: '确认不通过', WITHDRAWN: '已撤回' })[status]
}
function formatTime(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '-'
}
function extraOpinionText(opinion: Opinion): string {
  const template = document.createElement('template')
  template.innerHTML = opinion.richText || opinion.comment || ''
  template.content.querySelectorAll('img').forEach((image) => image.remove())
  return template.innerHTML.trim()
}
function extraOpinionImages(opinion: Opinion): string[] {
  const template = document.createElement('template')
  template.innerHTML = opinion.richText || opinion.comment || ''
  return Array.from(template.content.querySelectorAll('img'))
    .map((image) => image.getAttribute('src') || '')
    .filter(Boolean)
}
async function loadExtraOpinions(): Promise<void> {
  extraOpinionsLoading.value = true
  try {
    let page = await reviewApi.listOpinions(props.taskId, {
      sourceType: OpinionSource.MUTUAL_EXTRA, scene: 'REVIEW_WORKSPACE', pageNo: extraOpinionPagination.pageNo, pageSize: extraOpinionPagination.pageSize
    })
    const lastPage = Math.max(1, Math.ceil(page.total / page.pageSize))
    if (!page.items.length && page.total > 0 && page.pageNo > lastPage) {
      extraOpinionPagination.pageNo = lastPage
      page = await reviewApi.listOpinions(props.taskId, {
        sourceType: OpinionSource.MUTUAL_EXTRA, scene: 'REVIEW_WORKSPACE', pageNo: extraOpinionPagination.pageNo, pageSize: extraOpinionPagination.pageSize
      })
    }
    extraOpinions.value = page.items
    extraOpinionPagination.total = page.total
    extraOpinionPagination.pageNo = page.pageNo
  } catch {
    extraOpinions.value = []
    extraOpinionPagination.total = 0
  } finally {
    extraOpinionsLoading.value = false
  }
}
async function confirmExtraOpinion(opinion: Opinion, passed: boolean): Promise<void> {
  try {
    await reviewApi.confirmOpinion(opinion.id, { passed })
    await loadExtraOpinions()
    extraOpinionMessage.value = passed ? '已确认额外意见通过。' : '已退回设计者重新答复。'
  } catch (cause) {
    extraOpinionMessage.value = cause instanceof Error ? cause.message : '确认额外意见失败。'
  }
}
async function raiseExtraOpinion(): Promise<void> {
  const opinion = extraOpinionEditor.value?.innerHTML.trim() ?? ''
  const screenshots = extraOpinionScreenshotEditor.value?.innerHTML.trim() ?? ''
  if (![opinion, screenshots].some((value) => value && value !== '<br>')) {
    extraOpinionMessage.value = '请填写评审意见或粘贴问题截图。'
    return
  }
  const richText = `${opinion}${screenshots}`
  extraOpinionSaving.value = true
  extraOpinionMessage.value = ''
  try {
    await reviewApi.raiseOpinion(props.taskId, { sourceType: OpinionSource.MUTUAL_EXTRA, severity: extraOpinionForm.severity, comment: richText, richText })
    if (extraOpinionEditor.value) extraOpinionEditor.value.innerHTML = ''
    if (extraOpinionScreenshotEditor.value) extraOpinionScreenshotEditor.value.innerHTML = ''
    extraOpinionMessage.value = '额外评审意见已提交。'
    extraOpinionPagination.pageNo = 1
    await loadExtraOpinions()
  } catch (cause) {
    extraOpinionMessage.value = cause instanceof Error ? cause.message : '额外评审意见提交失败。'
  } finally {
    extraOpinionSaving.value = false
  }
}
async function submit(): Promise<void> {
  const invalidItems = checkItems.value
    .map((item) => [item, validateItem(item)] as const)
    .filter((entry): entry is readonly [CheckItemListItem, string] => Boolean(entry[1]))
  if (invalidItems.length) {
    invalidItems.forEach(([item, feedback]) => { itemFeedbacks[item.id] = { text: feedback } })
    message.value = ''
    return
  }
  message.value = ''; saving.value = true
  try {
    const entries = checkItems.value.map((item) => {
      const value = edit(item)
      return checkItemPayload(item.id, value)
    })
    await reviewApi.submitCheckItems(props.taskId, entries)
    entries.forEach((entry) => persistedItemIds.add(entry.itemId))
    message.value = '互检结果已整体提交。'; emit('submitted')
  } catch (cause) { message.value = cause instanceof Error ? cause.message : '互检结果提交失败。' } finally { saving.value = false }
}
function checkItemPayload(itemId: number, value: EditValue): { itemId: number; result: NonNullable<EditValue['result']>; comment?: string; richText?: string } {
  if (!value.result) throw new Error('请选择检查结果。')
  // 合格代表该固定项已处理完毕，不保留上一轮不合格时录入的意见和截图。
  if (value.result === 'PASS') return { itemId, result: value.result }
  return { itemId, result: value.result, comment: value.comment || undefined, richText: value.richText || undefined }
}
async function saveItem(item: CheckItemListItem): Promise<void> {
  const value = edit(item)
  const validationMessage = validateItem(item)
  if (validationMessage) {
    itemFeedbacks[item.id] = { text: validationMessage }
    return
  }
  savingItemId.value = item.id
  clearItemFeedback(item.id)
  try {
    const payload = checkItemPayload(item.id, value)
    await reviewApi.submitCheckItem(props.taskId, item.id, payload)
    itemFeedbacks[item.id] = {
      text: value.result === 'PASS'
        ? '已保存为合格；原不合格意见已视为处理完成。'
        : `已保存为${resultLabel(value.result)}。`,
      success: true
    }
    persistedItemIds.add(item.id)
    emit('submitted')
  } catch (cause) {
    itemFeedbacks[item.id] = { text: cause instanceof Error ? cause.message : '检查项保存失败。' }
  } finally {
    savingItemId.value = undefined
  }
}
watch(() => props.categories, (categories) => {
  const currentItemIds = new Set(categories.flatMap((category) => category.items.map((item) => item.id)))
  Object.keys(edits).map(Number).filter((itemId) => !currentItemIds.has(itemId)).forEach((itemId) => delete edits[itemId])
  Object.keys(itemFeedbacks).map(Number).filter((itemId) => !currentItemIds.has(itemId)).forEach((itemId) => delete itemFeedbacks[itemId])
  categories.forEach((category) => category.items.forEach((item) => {
    // 保存一项后的局部刷新不能覆盖其他子项尚未提交的选择和输入内容。
    // 仅首次载入、刚保存的项或已从列表移除后重新出现的项使用服务端值回填。
    if (!edits[item.id] || persistedItemIds.has(item.id)) {
      edits[item.id] = { result: item.opinion?.result, comment: item.opinion?.comment ?? '', richText: item.opinion?.richText ?? '' }
      persistedItemIds.delete(item.id)
    }
  }))
}, { deep: true, immediate: true })
onMounted(loadExtraOpinions)
</script>

<template>
  <section class="card mutual-check-form">
    <div class="section-head"><div><h2>互检单</h2><p>按检查大类及其子项填写。结果、文字意见和问题截图分别保存并回显；不合格或 NC 项必须填写文字意见。</p></div></div>
    <div class="mutual-groups">
      <article v-for="category in categories" :key="category.category.id" class="mutual-category">
        <header class="mutual-category-head"><span class="category-number">{{ category.category.sortNo }}</span><div><span class="category-badge">类别</span><b>{{ category.category.itemName }}</b><small>{{ category.items.length }} 个检查项</small></div></header>
        <div class="mutual-items">
          <section v-for="item in category.items" :key="item.id" class="mutual-item">
            <div class="mutual-item-head"><span class="item-number">{{ item.sortNo }}</span><div class="mutual-item-title">{{ item.itemName }}</div><div class="result-control"><select v-model="edit(item).result" @change="clearItemFeedback(item.id)"><option :value="undefined" disabled>请选择结果</option><option value="PASS">合格</option><option value="FAIL">不合格</option><option value="NC">NC</option></select><span class="result-badge" :class="edit(item).result?.toLowerCase()">{{ resultLabel(edit(item).result) }}</span><button class="btn compact primary" :disabled="saving || savingItemId === item.id" @click="saveItem(item)">{{ savingItemId === item.id ? '保存中…' : '保存' }}</button></div></div>
            <p v-if="itemFeedbacks[item.id]" class="mutual-item-feedback" :class="{ success: itemFeedbacks[item.id]?.success }">{{ itemFeedbacks[item.id]?.text }}</p>
            <div v-if="edit(item).result && edit(item).result !== 'PASS'" class="mutual-detail-grid">
              <label>意见 <textarea v-model="edit(item).comment" rows="3" placeholder="请填写具体意见 *" /></label>
              <label>问题截图 <div class="mutual-screenshot-editor" contenteditable="true" data-placeholder="Ctrl + V 粘贴问题截图" :innerHTML="screenshotHtml(item)" @input="handleScreenshotInput(item, $event)" @paste="handleOpinionPaste(item, $event)" /></label>
            </div>
            <div v-else-if="edit(item).result === 'PASS'" class="mutual-pass-note">该子项已标记为合格。</div>
            <div v-else class="mutual-unselected-note">请选择检查结果后再保存；不合格或 NC 时需填写意见。</div>
          </section>
        </div>
      </article>
      <div v-if="!checkItems.length" class="empty">当前任务尚未生成互检检查项</div>
    </div>
    <p v-if="message" :class="message === '互检结果已整体提交。' ? 'success-text' : 'form-error'">{{ message }}</p><footer class="form-footer"><button class="btn primary" :disabled="saving || !checkItems.length" @click="submit">{{ saving ? '正在提交…' : '提交互检结果' }}</button></footer>
  </section>
  <section class="card mutual-review-workspace">
    <div class="section-head">
      <div><h2>互检评审工作台</h2><p>用于补充不属于固定检查项的额外问题。</p></div>
      <button class="btn primary" :disabled="extraOpinionSaving" @click="raiseExtraOpinion">{{ extraOpinionSaving ? '正在提交…' : '提交评审意见' }}</button>
    </div>
    <div class="extra-opinion-grid">
      <label class="extra-screenshot-field"><b>问题截图</b><div ref="extraOpinionScreenshotEditor" class="mutual-screenshot-editor extra-screenshot-editor" contenteditable="true" data-placeholder="Ctrl + V 粘贴问题截图" @paste="handleExtraOpinionScreenshotPaste" /></label>
      <div class="extra-opinion-fields">
        <label>问题等级 *<select v-model="extraOpinionForm.severity"><option :value="OpinionSeverity.GENERAL">一般</option><option :value="OpinionSeverity.SERIOUS">严重</option><option :value="OpinionSeverity.MINOR">轻微</option></select></label>
        <label>具体评审意见 *<div ref="extraOpinionEditor" class="extra-opinion-editor" contenteditable="true" data-placeholder="请输入具体、可执行的评审意见" /></label>
      </div>
    </div>
    <p v-if="extraOpinionMessage" :class="extraOpinionMessage === '额外评审意见已提交。' ? 'success-text' : 'form-error'">{{ extraOpinionMessage }}</p>
    <div class="extra-opinion-list">
      <div class="extra-opinion-list-head"><h3>额外意见列表</h3></div>
      <article v-for="opinion in extraOpinions" :key="opinion.id" class="extra-opinion-row">
        <div class="extra-opinion-main"><div class="extra-opinion-meta"><span class="severity-chip" :class="opinion.severity.toLowerCase()">{{ severityLabel(opinion.severity) }}</span><span class="opinion-status">{{ opinionStatusLabel(opinion.status) }}</span><span>提出时间：{{ formatTime(opinion.createdAt) }}</span></div>
          <div class="extra-opinion-content" v-html="extraOpinionText(opinion) || (extraOpinionImages(opinion).length ? '' : opinion.comment)" />
          <div v-if="opinion.status === 'PENDING_CONFIRMATION'" class="extra-opinion-actions"><button class="btn compact" @click="confirmExtraOpinion(opinion, true)">通过</button><button class="btn compact danger" @click="confirmExtraOpinion(opinion, false)">不通过</button></div>
        </div>
        <aside v-if="extraOpinionImages(opinion).length" class="extra-opinion-rich-text" aria-label="问题截图"><img v-for="(image, index) in extraOpinionImages(opinion)" :key="`${opinion.id}-${index}`" :src="image" alt="问题截图" /></aside>
      </article>
      <p v-if="extraOpinionsLoading" class="empty">正在加载额外意见…</p>
      <p v-else-if="!extraOpinions.length" class="empty">暂无额外意见</p>
      <footer v-if="extraOpinionPagination.total > extraOpinionPagination.pageSize" class="extra-opinion-pagination">
        <button class="btn compact" :disabled="extraOpinionsLoading || extraOpinionPagination.pageNo <= 1" @click="extraOpinionPagination.pageNo--; loadExtraOpinions()">上一页</button>
        <span>第 {{ extraOpinionPagination.pageNo }} / {{ Math.ceil(extraOpinionPagination.total / extraOpinionPagination.pageSize) }} 页</span>
        <button class="btn compact" :disabled="extraOpinionsLoading || extraOpinionPagination.pageNo >= Math.ceil(extraOpinionPagination.total / extraOpinionPagination.pageSize)" @click="extraOpinionPagination.pageNo++; loadExtraOpinions()">下一页</button>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.mutual-review-workspace{margin-top:16px}.extra-opinion-grid{display:grid;grid-template-columns:340px minmax(0,1fr);gap:20px}.extra-screenshot-field,.extra-opinion-fields label{display:grid;gap:8px;color:#30384e;font-size:13px;font-weight:700}.extra-screenshot-field{min-height:260px;padding:14px;border:1px solid #e0e4ef;border-radius:10px;background:#fbfcff}.extra-screenshot-editor{min-height:190px;height:100%}.extra-opinion-fields{display:grid;grid-template-rows:auto minmax(170px,1fr);gap:16px}.extra-opinion-fields select{width:100%;padding:10px;border:1px solid #dce1ec;border-radius:8px;background:#fff}.extra-opinion-editor{min-height:170px;padding:12px;border:1px solid #dce1ec;border-radius:8px;background:#fbfcff;line-height:1.6;outline:none}.extra-opinion-editor:empty:before{content:attr(data-placeholder);color:#858ba0;font-weight:400}.extra-opinion-list{margin-top:22px;padding-top:18px;border-top:1px solid #e7eaf1}.extra-opinion-list-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.extra-opinion-list-head h3{margin:0;color:#29334b;font-size:15px}.extra-opinion-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(180px,280px);gap:24px;padding:16px 0;border-bottom:1px solid #eef0f5}.extra-opinion-meta{display:flex;align-items:center;flex-wrap:wrap;gap:8px;color:#7c8395;font-size:12px}.severity-chip,.opinion-status{padding:4px 8px;border-radius:13px;font-size:12px}.severity-chip{background:#eaf3ff;color:#2870cb}.severity-chip.serious{background:#fff0f0;color:#d44747}.severity-chip.minor{background:#edf8ff;color:#2d88d8}.opinion-status{background:#fff5e8;color:#bd7620}.extra-opinion-content{margin-top:10px;color:#30384e;line-height:1.7}.extra-opinion-rich-text{display:grid;align-content:start;gap:8px}.extra-opinion-rich-text img{display:block;width:100%;max-height:180px;border-radius:6px;object-fit:contain;background:#f5f7fb}.extra-opinion-actions{display:flex;gap:8px;margin-top:12px}.extra-opinion-pagination{display:flex;justify-content:flex-end;align-items:center;gap:10px;margin-top:14px;color:#737c91;font-size:13px}
.mutual-groups{display:grid;gap:14px}.mutual-category{overflow:hidden;border:1px solid #dfe3ed;border-radius:11px;background:#fff}.mutual-category-head{display:flex;align-items:center;gap:13px;min-height:66px;padding:0 17px;border-bottom:1px solid #e9ecf3;background:#fafbfe}.category-number,.item-number{display:grid;place-items:center;flex:none;width:28px;height:28px;border-radius:50%;background:#f1f3f8;color:#606981;font-size:12px}.category-badge{display:inline-block;margin-right:10px;padding:5px 8px;border-radius:6px;background:#eeeaff;color:#6557ce;font-size:12px}.mutual-category-head b{color:#273149;font-size:16px}.mutual-category-head small{margin-left:9px;color:#7b8295;font-size:13px}.mutual-items{display:grid}.mutual-item{padding:0 17px;border-bottom:1px solid #edf0f4}.mutual-item:last-child{border-bottom:0}.mutual-item-head{display:grid;grid-template-columns:30px minmax(0,1fr) auto;align-items:center;gap:10px;min-height:58px}.mutual-item-title{color:#30384e;font-size:14px;font-weight:600;line-height:1.55}.mutual-item-title :deep(img){display:block;max-width:100%;max-height:220px;margin:8px 0;border-radius:4px;object-fit:contain}.result-control{display:flex;align-items:center;gap:9px}.result-control select{width:116px;padding:7px 9px;border:1px solid #dce1ec;border-radius:7px;background:#fff}.result-badge{min-width:46px;padding:4px 8px;border-radius:13px;background:#f1f3f8;color:#687087;text-align:center;font-size:12px}.result-badge.pass{background:#eaf8ef;color:#268052}.result-badge.fail{background:#fff0ef;color:#cc4845}.result-badge.nc{background:#fff6e7;color:#b57318}.mutual-item-feedback{margin:-4px 0 12px 40px;color:#c84343;font-size:12px;line-height:1.5}.mutual-item-feedback.success{color:#278355}.mutual-detail-grid{display:grid;grid-template-columns:minmax(240px,1fr) minmax(240px,1fr);gap:14px;padding:0 0 16px}.mutual-detail-grid label{display:grid;gap:7px;color:#525a70;font-size:13px;font-weight:700}.mutual-detail-grid textarea{width:100%;min-height:106px;padding:10px;border:1px solid #dce1ec;border-radius:8px;resize:vertical;outline:0}.mutual-screenshot-editor{min-height:106px;padding:10px;border:1px dashed #bfc8df;border-radius:8px;background:#fbfcff;line-height:1.6;outline:0}.mutual-screenshot-editor:empty:before{content:attr(data-placeholder);color:#858ba0;font-size:12px;font-weight:400}.mutual-screenshot-editor :deep(img){display:block;max-width:100%;max-height:180px;margin:6px 0;border-radius:4px;object-fit:contain}.mutual-pass-note,.mutual-unselected-note{padding:0 0 15px 40px;font-size:12px}.mutual-pass-note{color:#548064}.mutual-unselected-note{color:#858ba0}@media(max-width:760px){.extra-opinion-grid,.extra-opinion-row{grid-template-columns:1fr}.extra-screenshot-field{min-height:180px}.extra-screenshot-editor{min-height:140px}.mutual-detail-grid{grid-template-columns:1fr}.mutual-item-head{grid-template-columns:30px minmax(0,1fr)}.result-control{grid-column:2;justify-self:start;padding-bottom:12px}.mutual-category-head{align-items:flex-start;padding-top:15px;padding-bottom:15px}}
</style>
