<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { reviewApi } from '@/api/review-api'
import type { CheckItem } from '@/api/types'

const props = defineProps<{ taskId: number; items: CheckItem[] }>()
const emit = defineEmits<{ submitted: [] }>()
type EditValue = { result: 'PASS' | 'FAIL' | 'NOT_APPLICABLE'; comment: string; richText: string }
const edits = reactive<Record<number, EditValue>>({})
const message = ref('')
const saving = ref(false)

function edit(item: CheckItem): EditValue {
  return edits[item.id] ??= { result: item.result ?? 'PASS', comment: item.comment ?? '', richText: item.richText ?? '' }
}
function updateRichText(item: CheckItem, event: Event): void { edit(item).richText = (event.currentTarget as HTMLElement).innerHTML }
function handleOpinionPaste(item: CheckItem, event: ClipboardEvent): void {
  const images = Array.from(event.clipboardData?.files ?? []).filter((file) => file.type.startsWith('image/'))
  if (!images.length) return
  event.preventDefault()
  const editor = event.currentTarget as HTMLElement
  images.forEach((image) => {
    const reader = new FileReader()
    reader.onload = () => { editor.innerHTML += `<img src="${String(reader.result)}" alt="问题截图" />`; edit(item).richText = editor.innerHTML }
    reader.readAsDataURL(image)
  })
}
async function submit(): Promise<void> {
  message.value = ''; saving.value = true
  try {
    const entries = props.items.map((item) => {
      const value = edit(item)
      return { itemId: item.id, result: value.result, comment: value.comment || undefined, richText: value.richText || undefined,
        attachmentFileIds: item.attachments.map((file) => file.fileId) }
    })
    await reviewApi.submitCheckItems(props.taskId, entries); message.value = '互检结果已整体提交。'; emit('submitted')
  } catch (cause) { message.value = cause instanceof Error ? cause.message : '互检结果提交失败。' } finally { saving.value = false }
}
watch(() => props.items, () => { Object.keys(edits).forEach((key) => delete edits[Number(key)]) }, { deep: true })
</script>

<template>
  <section class="card mutual-check-form">
    <div class="section-head"><div><h2>互检单</h2><p>不合格项请填写提取意见，可直接粘贴文字和问题截图。图文以一段富文本字符串保存并回显，不需要图片 URL 或独立上传。</p></div></div>
    <div class="table-wrap"><table class="data-table mutual-table"><thead><tr><th>序号</th><th>类别</th><th>检查项</th><th>结果</th><th>提取意见</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>{{ item.sortNo }}</td><td>{{ item.categoryName || item.parentItemKey || '—' }}</td><td><b>{{ item.itemName }}</b></td><td><select v-model="edit(item).result"><option value="PASS">合格</option><option value="FAIL">不合格</option><option value="NOT_APPLICABLE">不适用</option></select></td><td><div v-if="edit(item).result === 'FAIL'" class="mutual-rich-editor" contenteditable="true" :data-placeholder="'请输入提取意见，可 Ctrl + V 粘贴截图'" :innerHTML="edit(item).richText" @input="updateRichText(item, $event)" @paste="handleOpinionPaste(item, $event)" /><input v-else-if="edit(item).result === 'NOT_APPLICABLE'" v-model="edit(item).comment" placeholder="填写不适用说明 *" /><span v-else class="muted">—</span></td></tr><tr v-if="!items.length"><td colspan="5" class="empty">当前任务尚未生成互检检查项</td></tr></tbody></table></div>
    <p v-if="message" :class="message === '互检结果已整体提交。' ? 'success-text' : 'form-error'">{{ message }}</p><footer class="form-footer"><button class="btn primary" :disabled="saving || !items.length" @click="submit">{{ saving ? '正在提交…' : '提交互检结果' }}</button></footer>
  </section>
</template>

<style scoped>
.mutual-rich-editor{min-height:82px;padding:9px;border:1px dashed #bfc8df;border-radius:7px;background:#fff;line-height:1.6;outline:none}.mutual-rich-editor:empty:before{content:attr(data-placeholder);color:#858ba0;font-size:12px}.mutual-rich-editor :deep(img){display:block;max-width:100%;max-height:180px;margin:6px 0;border-radius:4px;object-fit:contain}.mutual-table td:last-child{min-width:320px}
</style>
