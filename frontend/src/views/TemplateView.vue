<script setup lang="ts">
import { ref } from 'vue'
import { reviewApi } from '@/api/review-api'

const fileInput = ref<HTMLInputElement>()
const importing = ref(false)
const message = ref('')
const error = ref('')
const form = ref({ reviewType: 'PCB', itemKey: '', parentItemKey: '', itemName: '', sortNo: 0 })
const saving = ref(false)

async function importWorkbook(event: Event): Promise<void> {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  importing.value = true; error.value = ''; message.value = ''
  try { const result = await reviewApi.importTemplate(file); message.value = `导入完成：共 ${result.totalRows} 行，新增 ${result.createdCount} 条，更新 ${result.updatedCount} 条。` } catch (cause) { error.value = cause instanceof Error ? cause.message : '导入失败' } finally { importing.value = false; if (fileInput.value) fileInput.value.value = '' }
}
async function saveTemplate(): Promise<void> {
  if (!form.value.itemKey || !form.value.itemName) { error.value = '请填写检查项编码和检查项名称。'; return }
  saving.value = true; error.value = ''; message.value = ''
  try { await reviewApi.createTemplate({ ...form.value, parentItemKey: form.value.parentItemKey || undefined }); message.value = '检查项模板已创建。'; form.value = { reviewType: 'PCB', itemKey: '', parentItemKey: '', itemName: '', sortNo: 0 } } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' } finally { saving.value = false }
}
</script>

<template>
  <div class="page-head"><div><h1>互检管理</h1><p>支持维护检查项模板，并按 Excel 批量导入。</p></div><button class="btn primary" :disabled="importing" @click="fileInput?.click()">{{ importing ? '导入中…' : '导入 Excel' }}</button><input ref="fileInput" class="hidden" type="file" accept=".xlsx" @change="importWorkbook" /></div>
  <section class="card"><h2>Excel 导入规范</h2><p>工作簿首个工作表必须包含表头：<code>评审类型</code>、<code>检查项编码</code>、<code>父级检查项编码</code>、<code>检查项名称</code>、<code>排序号</code>、<code>是否启用</code>。</p><p>评审类型支持 PCB/SCHEMATIC；是否启用支持 是/否、true/false 或 1/0。任何一行不合法时，后端会整体回滚。</p><p v-if="message" class="success-text">{{ message }}</p><p v-if="error" class="form-error">{{ error }}</p></section>
  <section class="card"><h2>新增单个检查项</h2><p class="muted">后端当前未提供模板列表查询接口，因此此处仅提供创建和导入入口。</p><div class="form-grid compact-grid"><label>评审类型<select v-model="form.reviewType"><option>PCB</option><option>SCHEMATIC</option></select></label><label>检查项编码<input v-model="form.itemKey" placeholder="PCB-001" /></label><label>父级编码<input v-model="form.parentItemKey" placeholder="可选" /></label><label>检查项名称<input v-model="form.itemName" placeholder="例如：地平面覆盖" /></label><label>排序号<input v-model.number="form.sortNo" type="number" min="0" /></label></div><div class="form-footer"><button class="btn primary" :disabled="saving" @click="saveTemplate">{{ saving ? '保存中…' : '保存检查项' }}</button></div></section>
</template>
