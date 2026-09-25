<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { reviewApi } from '@/api/review-api'
import type { CheckItemCategory, CheckItemListItem } from '@/api/types'

const props = defineProps<{ taskId: number; categories: CheckItemCategory[] }>()
const emit = defineEmits<{ submitted: [] }>()
type EditValue = { result: 'PASS' | 'FAIL' | 'NC'; comment: string; richText: string }
const edits = reactive<Record<number, EditValue>>({})
const message = ref('')
const saving = ref(false)
const checkItems = computed<CheckItemListItem[]>(() => props.categories.flatMap((category) => category.items))

function edit(item: CheckItemListItem): EditValue { return edits[item.id] }
function resultLabel(result: EditValue['result']): string { return result === 'PASS' ? '合格' : result === 'FAIL' ? '不合格' : 'NC' }
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
async function submit(): Promise<void> {
  message.value = ''; saving.value = true
  try {
    const entries = checkItems.value.map((item) => {
      const value = edit(item)
      return { itemId: item.id, result: value.result, comment: value.comment || undefined, richText: value.richText || undefined }
    })
    await reviewApi.submitCheckItems(props.taskId, entries); message.value = '互检结果已整体提交。'; emit('submitted')
  } catch (cause) { message.value = cause instanceof Error ? cause.message : '互检结果提交失败。' } finally { saving.value = false }
}
watch(() => props.categories, (categories) => {
  Object.keys(edits).forEach((key) => delete edits[Number(key)])
  categories.forEach((category) => category.items.forEach((item) => {
    edits[item.id] = { result: item.opinion?.result ?? 'PASS', comment: item.opinion?.comment ?? '', richText: item.opinion?.richText ?? '' }
  }))
}, { deep: true, immediate: true })
</script>

<template>
  <section class="card mutual-check-form">
    <div class="section-head"><div><h2>互检单</h2><p>按检查大类及其子项填写。结果、文字意见和问题截图分别保存并回显；不合格或 NC 项必须填写文字意见。</p></div></div>
    <div class="mutual-groups">
      <article v-for="category in categories" :key="category.category.id" class="mutual-category">
        <header class="mutual-category-head"><span class="category-number">{{ category.category.sortNo }}</span><div><span class="category-badge">类别</span><b>{{ category.category.itemName }}</b><small>{{ category.items.length }} 个检查项</small></div></header>
        <div class="mutual-items">
          <section v-for="item in category.items" :key="item.id" class="mutual-item">
            <div class="mutual-item-head"><span class="item-number">{{ item.sortNo }}</span><b>{{ item.itemName }}</b><div class="result-control"><select v-model="edit(item).result"><option value="PASS">合格</option><option value="FAIL">不合格</option><option value="NC">NC</option></select><span class="result-badge" :class="edit(item).result.toLowerCase()">{{ resultLabel(edit(item).result) }}</span></div></div>
            <div v-if="edit(item).result !== 'PASS'" class="mutual-detail-grid">
              <label>意见 <textarea v-model="edit(item).comment" rows="3" placeholder="请填写具体意见 *" /></label>
              <label>问题截图 <div class="mutual-screenshot-editor" contenteditable="true" data-placeholder="Ctrl + V 粘贴问题截图" :innerHTML="screenshotHtml(item)" @input="handleScreenshotInput(item, $event)" @paste="handleOpinionPaste(item, $event)" /></label>
            </div>
            <div v-else class="mutual-pass-note">该子项已标记为合格。</div>
          </section>
        </div>
      </article>
      <div v-if="!checkItems.length" class="empty">当前任务尚未生成互检检查项</div>
    </div>
    <p v-if="message" :class="message === '互检结果已整体提交。' ? 'success-text' : 'form-error'">{{ message }}</p><footer class="form-footer"><button class="btn primary" :disabled="saving || !checkItems.length" @click="submit">{{ saving ? '正在提交…' : '提交互检结果' }}</button></footer>
  </section>
</template>

<style scoped>
.mutual-groups{display:grid;gap:14px}.mutual-category{overflow:hidden;border:1px solid #dfe3ed;border-radius:11px;background:#fff}.mutual-category-head{display:flex;align-items:center;gap:13px;min-height:66px;padding:0 17px;border-bottom:1px solid #e9ecf3;background:#fafbfe}.category-number,.item-number{display:grid;place-items:center;flex:none;width:28px;height:28px;border-radius:50%;background:#f1f3f8;color:#606981;font-size:12px}.category-badge{display:inline-block;margin-right:10px;padding:5px 8px;border-radius:6px;background:#eeeaff;color:#6557ce;font-size:12px}.mutual-category-head b{color:#273149;font-size:16px}.mutual-category-head small{margin-left:9px;color:#7b8295;font-size:13px}.mutual-items{display:grid}.mutual-item{padding:0 17px;border-bottom:1px solid #edf0f4}.mutual-item:last-child{border-bottom:0}.mutual-item-head{display:grid;grid-template-columns:30px minmax(0,1fr) auto;align-items:center;gap:10px;min-height:58px}.mutual-item-head>b{color:#30384e;font-size:14px;font-weight:600}.result-control{display:flex;align-items:center;gap:9px}.result-control select{width:116px;padding:7px 9px;border:1px solid #dce1ec;border-radius:7px;background:#fff}.result-badge{min-width:46px;padding:4px 8px;border-radius:13px;background:#eaf8ef;color:#268052;text-align:center;font-size:12px}.result-badge.fail{background:#fff0ef;color:#cc4845}.result-badge.nc{background:#fff6e7;color:#b57318}.mutual-detail-grid{display:grid;grid-template-columns:minmax(240px,1fr) minmax(240px,1fr);gap:14px;padding:0 0 16px}.mutual-detail-grid label{display:grid;gap:7px;color:#525a70;font-size:13px;font-weight:700}.mutual-detail-grid textarea{width:100%;min-height:106px;padding:10px;border:1px solid #dce1ec;border-radius:8px;resize:vertical;outline:0}.mutual-screenshot-editor{min-height:106px;padding:10px;border:1px dashed #bfc8df;border-radius:8px;background:#fbfcff;line-height:1.6;outline:none}.mutual-screenshot-editor:empty:before{content:attr(data-placeholder);color:#858ba0;font-size:12px;font-weight:400}.mutual-screenshot-editor :deep(img){display:block;max-width:100%;max-height:180px;margin:6px 0;border-radius:4px;object-fit:contain}.mutual-pass-note{padding:0 0 15px 40px;color:#548064;font-size:12px}@media(max-width:760px){.mutual-detail-grid{grid-template-columns:1fr}.mutual-item-head{grid-template-columns:30px minmax(0,1fr)}.result-control{grid-column:2;justify-self:start;padding-bottom:12px}.mutual-category-head{align-items:flex-start;padding-top:15px;padding-bottom:15px}}
</style>
