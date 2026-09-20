<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { reviewApi } from '@/api/review-api'
import type { CheckItem } from '@/api/types'

const props = defineProps<{ taskId: number; items: CheckItem[] }>()
const emit = defineEmits<{ submitted: [] }>()
const edits = reactive<Record<number, { result: 'PASS' | 'FAIL' | 'NOT_APPLICABLE'; comment: string }>>({})
const files = reactive<Record<number, File[]>>({})
const message = ref('')
const saving = ref(false)
function edit(item: CheckItem): { result: 'PASS' | 'FAIL' | 'NOT_APPLICABLE'; comment: string } { return edits[item.id] ??= { result: item.result ?? 'PASS', comment: item.comment ?? '' } }
function selectFiles(itemId: number, event: Event): void { files[itemId] = Array.from((event.target as HTMLInputElement).files ?? []) }
async function digest(file: File): Promise<string> { const hash = await crypto.subtle.digest('SHA-256', await file.arrayBuffer()); return Array.from(new Uint8Array(hash)).map(value => value.toString(16).padStart(2, '0')).join('') }
async function submit(): Promise<void> {
  message.value = ''; saving.value = true
  try {
    const entries = [] as Array<{ itemId: number; result: string; comment?: string; attachmentFileIds: number[]; version: number }>
    for (const item of props.items) {
      const value = edit(item); const attachmentFileIds: number[] = []
      for (let index = 0; index < (files[item.id] ?? []).length; index++) {
        const file = files[item.id][index]; const session = await reviewApi.createUploadSession(props.taskId, 'MUTUAL_CHECK_ATTACHMENT')
        const registered = await reviewApi.registerFile(props.taskId, { uploadSessionId: session.uploadSessionId, category: 'MUTUAL_CHECK_ATTACHMENT', businessFileKey: `MUTUAL_CHECK_${item.id}_${Date.now()}_${index}`, fileName: file.name, fileSize: file.size, md5: await digest(file) })
        attachmentFileIds.push(registered.id)
      }
      entries.push({ itemId: item.id, result: value.result, comment: value.comment || undefined, attachmentFileIds: attachmentFileIds.length ? attachmentFileIds : item.attachments.map(file => file.fileId), version: item.version })
    }
    await reviewApi.submitCheckItems(props.taskId, entries); message.value = '互检结果已整体提交。'; emit('submitted')
  } catch (cause) { message.value = cause instanceof Error ? cause.message : '互检结果提交失败。' } finally { saving.value = false }
}
watch(() => props.items, () => { Object.keys(edits).forEach(key => delete edits[Number(key)]) }, { deep: true })
</script>

<template>
  <section class="card mutual-check-form"><div class="section-head"><div><h2>互检单</h2><p>不合格项必须填写检查意见并上传至少一张问题图片；提交后将自动创建对应的互检意见。</p></div></div>
    <div class="table-wrap"><table class="data-table mutual-table"><thead><tr><th>序号</th><th>类别</th><th>检查项</th><th>结果</th><th>图片</th><th>意见</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>{{ item.sortNo }}</td><td>{{ item.categoryName || item.parentItemKey || '—' }}</td><td><b>{{ item.itemName }}</b></td><td><select v-model="edit(item).result"><option value="PASS">合格</option><option value="FAIL">不合格</option><option value="NOT_APPLICABLE">不适用</option></select></td><td><input v-if="edit(item).result === 'FAIL'" type="file" accept="image/*" multiple @change="selectFiles(item.id, $event)" /><span v-else class="muted">—</span><small v-if="item.attachments.length">已关联：{{ item.attachments.map(file => file.fileName).join('、') }}</small></td><td><input v-model="edit(item).comment" :placeholder="edit(item).result === 'FAIL' ? '填写检查意见 *' : '填写检查意见'" /></td></tr><tr v-if="!items.length"><td colspan="6" class="empty">当前任务尚未生成互检检查项</td></tr></tbody></table></div>
    <p v-if="message" :class="message === '互检结果已整体提交。' ? 'success-text' : 'form-error'">{{ message }}</p><footer class="form-footer"><button class="btn primary" :disabled="saving || !items.length" @click="submit">{{ saving ? '正在提交…' : '提交互检结果' }}</button></footer>
  </section>
</template>
