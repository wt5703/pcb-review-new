<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { reviewApi } from '@/api/review-api'
import type { TemplateListCategory } from '@/api/types'

type EditorMode = 'create-category' | 'edit-category' | 'edit-item' | null
type EditableItem = { id?: number; itemName: string }

const fileInput = ref<HTMLInputElement>()
const importing = ref(false)
const saving = ref(false)
const loading = ref(true)
const error = ref('')
const message = ref('')
const reviewType = ref('PCB')
const categories = ref<TemplateListCategory[]>([])
const editorMode = ref<EditorMode>(null)
const editingId = ref<number>()
const editingGroupId = ref<number>()
const activeItemIndex = ref<number>()
const editor = reactive({ categoryName: '', items: [] as EditableItem[] })
const categoryCount = computed(() => categories.value.length)
const activeItem = computed(() => activeItemIndex.value === undefined ? undefined : editor.items[activeItemIndex.value])

function resetEditor(): void {
  editingId.value = undefined
  editingGroupId.value = undefined
  activeItemIndex.value = undefined
  Object.assign(editor, { categoryName: '', items: [{ itemName: '' }] })
}

function fillEditor(group: TemplateListCategory): void {
  editingId.value = group.category.id
  editingGroupId.value = group.category.id
  Object.assign(editor, {
    categoryName: group.category.itemName,
    items: group.items.map((item) => ({ id: item.id, itemName: item.itemName }))
  })
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try { categories.value = await reviewApi.listTemplates(reviewType.value) } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载互检模板失败' } finally { loading.value = false }
}

function closeEditor(): void { editorMode.value = null; resetEditor() }
function openCreate(): void { resetEditor(); editor.items = []; editorMode.value = 'create-category' }
function openCategoryEditor(group: TemplateListCategory): void { fillEditor(group); editorMode.value = 'edit-category' }
function openItemEditor(group: TemplateListCategory, itemId: number): void {
  fillEditor(group)
  activeItemIndex.value = editor.items.findIndex((item) => item.id === itemId)
  editorMode.value = 'edit-item'
}
function openNewItem(group: TemplateListCategory): void {
  fillEditor(group)
  editor.items.push({ itemName: '' })
  activeItemIndex.value = editor.items.length - 1
  editorMode.value = 'edit-item'
}
function removeLocalItem(): void {
  if (activeItemIndex.value === undefined) return
  editor.items.splice(activeItemIndex.value, 1)
  activeItemIndex.value = undefined
  editorMode.value = 'edit-category'
}

async function save(): Promise<void> {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const categoryName = editor.categoryName.trim()
    const items = editor.items.map((item) => ({ ...item, itemName: item.itemName.trim() }))
    if (editorMode.value === 'create-category') {
      await reviewApi.createTemplate({ reviewType: reviewType.value, categoryName, items: items.map(({ itemName }) => ({ itemName })) })
      message.value = '互检类别已创建。'
    } else if (editorMode.value === 'edit-item' && activeItem.value?.id) {
      await reviewApi.updateTemplate({ itemId: activeItem.value.id, itemName: activeItem.value.itemName.trim() })
      message.value = '检查项已保存。'
    } else if (editingId.value) {
      await reviewApi.updateTemplate({ itemId: editingId.value, itemName: categoryName, items: items.map(({ id, itemName }) => ({ itemId: id, itemName })) })
      message.value = editorMode.value === 'edit-item' ? '检查项已保存。' : '类别及检查项已更新。'
    }
    await load()
    closeEditor()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请填写类别和检查项名称。'
  } finally { saving.value = false }
}

async function removeTemplate(id: number, name: string, category: 'CATEGORY' | 'ITEM'): Promise<void> {
  const isCategory = category === 'CATEGORY'
  const target = isCategory ? `“${name}”及其检查项` : `检查项“${name}”`
  if (!window.confirm(`确认删除${target}吗？已创建任务不会被删除。`)) return
  try {
    await reviewApi.disableCheckItemTemplate(id, category)
    message.value = isCategory ? '类别已删除。' : '检查项已删除。'
    if (editingGroupId.value === id || activeItem.value?.id === id) closeEditor()
    await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '删除失败' }
}

async function importWorkbook(event: Event): Promise<void> {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  importing.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await reviewApi.importTemplate(file)
    message.value = `导入完成：共 ${result.totalRows} 行，新增 ${result.createdCount} 条，更新 ${result.updatedCount} 条。`
    await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '导入失败' } finally {
    importing.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="template-page">
    <header class="template-toolbar">
      <h1>互检单管理</h1>
      <div class="toolbar-actions">
        <button class="btn primary" @click="openCreate">＋ 新增类别</button>
        <button class="btn" :disabled="importing" @click="fileInput?.click()">{{ importing ? '导入中…' : '导入 Excel' }}</button>
        <input ref="fileInput" class="hidden" type="file" accept=".xlsx" @change="importWorkbook" />
      </div>
    </header>

    <p v-if="message" class="toast-message">{{ message }}</p>
    <p v-if="error" class="form-error template-error">{{ error }}</p>

    <form v-if="editorMode === 'create-category'" class="card category-editor" @submit.prevent="save">
      <div class="editor-title"><h2>新增类别</h2><button class="btn compact" type="button" @click="closeEditor">取消</button></div>
      <label>类别 *<input v-model="editor.categoryName" required placeholder="例如：高速信号" /></label>
      <div class="editor-title item-title"><b>检查项 *</b><button class="btn compact" type="button" @click="editor.items.push({ itemName: '' })">＋ 新增检查项</button></div>
      <div v-for="(item, index) in editor.items" :key="index" class="item-input-row"><input v-model="item.itemName" required placeholder="请输入检查项名称" /><button class="btn compact danger" type="button" @click="editor.items.splice(index, 1)">删除</button></div>
      <footer><button class="btn primary" :disabled="saving" type="submit">{{ saving ? '保存中…' : '保存类别' }}</button></footer>
    </form>

    <section v-if="loading" class="card loading">正在加载互检模板…</section>
    <section v-else class="card template-root">
      <div class="template-heading"><h2>互检单模板</h2><span class="tag">{{ categoryCount }} 个类别</span></div>
      <div class="template-list">
        <article v-for="group in categories" :key="group.category.id" class="template-group">
          <header class="group-header">
            <div><span class="category-badge">类别</span><b>{{ group.category.itemName }}</b><small>{{ group.items.length }} 个检查项</small></div>
            <div class="group-actions"><button class="btn compact" @click="openCategoryEditor(group)">编辑类别</button><button class="btn compact danger" @click="removeTemplate(group.category.id, group.category.itemName, 'CATEGORY')">删除</button></div>
          </header>

          <form v-if="editorMode === 'edit-category' && editingGroupId === group.category.id" class="category-editor inner-editor" @submit.prevent="save">
            <div class="editor-title"><h3>编辑类别</h3><button class="btn compact" type="button" @click="closeEditor">取消</button></div>
            <label>类别 *<input v-model="editor.categoryName" required /></label>
            <footer><button class="btn primary compact" :disabled="saving" type="submit">保存类别</button></footer>
          </form>

          <template v-for="(item, index) in group.items" :key="item.id">
            <div class="template-item"><span>{{ index + 1 }}</span><b>{{ item.itemName }}</b><button class="text-link" @click="openItemEditor(group, item.id)">编辑检查项</button></div>
            <form v-if="editorMode === 'edit-item' && editingGroupId === group.category.id && activeItem?.id === item.id" class="item-editor" @submit.prevent="save">
              <div class="editor-title"><h3>编辑检查项</h3><button class="btn compact" type="button" @click="closeEditor">取消</button></div>
              <label>检查项 *<div class="item-input-row"><input v-model="activeItem.itemName" required /><button class="btn compact danger" type="button" @click="removeTemplate(item.id, item.itemName, 'ITEM')">删除</button></div></label>
              <footer><button class="btn primary compact" :disabled="saving" type="submit">{{ saving ? '保存中…' : '保存检查项' }}</button></footer>
            </form>
          </template>

          <form v-if="editorMode === 'edit-item' && editingGroupId === group.category.id && activeItem && !activeItem.id" class="item-editor" @submit.prevent="save">
            <div class="editor-title"><h3>新增检查项</h3><button class="btn compact" type="button" @click="removeLocalItem">取消</button></div>
            <label>检查项 *<div class="item-input-row"><input v-model="activeItem.itemName" required placeholder="请输入检查项名称" /><button class="btn compact danger" type="button" @click="removeLocalItem">删除</button></div></label>
            <footer><button class="btn primary compact" :disabled="saving" type="submit">{{ saving ? '保存中…' : '保存检查项' }}</button></footer>
          </form>

          <div class="group-footer"><button class="btn compact" @click="openNewItem(group)">＋ 新增检查项</button></div>
        </article>
        <div v-if="!categories.length" class="empty">暂无模板类别，请新增类别或导入 Excel。</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.template-page{max-width:1400px;margin:0 auto}.template-toolbar{display:flex;align-items:center;justify-content:space-between;margin:8px 0 20px}.template-toolbar h1{margin:0;font-size:24px;letter-spacing:-.4px;color:#1d2740}.toolbar-actions,.group-actions{display:flex;gap:8px}.toolbar-actions .btn{height:38px}.template-error{margin:0 0 12px}.template-root{padding:18px}.template-heading,.editor-title{display:flex;justify-content:space-between;align-items:center;gap:12px}.template-heading{margin:4px 0 26px}.template-heading h2,.editor-title h2,.editor-title h3{margin:0;color:#1e2942}.template-heading h2{font-size:17px}.template-list{display:grid;gap:12px}.template-group{overflow:hidden;border:1px solid #dfe3ed;border-radius:11px;background:#fff}.template-group.disabled{opacity:.65}.group-header{display:flex;align-items:center;justify-content:space-between;gap:16px;min-height:68px;padding:0 18px;background:#fbfcff;border-bottom:1px solid #e8eaf1}.category-badge{display:inline-block;margin-right:12px;padding:5px 9px;border-radius:6px;background:#f0edff;color:#6657d9;font-size:12px}.group-header b{font-size:16px;color:#25304a}.group-header small{margin-left:9px;color:#737c91;font-size:13px}.template-item{display:grid;grid-template-columns:30px minmax(0,1fr) auto auto;align-items:center;gap:10px;min-height:52px;padding:0 18px;border-bottom:1px solid #eef0f5;color:#2f3850;font-size:13px}.template-item>span{display:grid;place-items:center;width:27px;height:27px;border-radius:50%;background:#f3f5f9;color:#616c84;font-size:12px}.template-item b{font-weight:500}.template-item em{font-style:normal;color:#bd7b2b;font-size:12px}.text-link{border:0;background:transparent;font-size:13px;white-space:nowrap}.group-footer{padding:11px 18px;background:#fff}.category-editor,.item-editor{padding:18px;border:1px solid #dde2ed;border-radius:10px;background:#fff;box-shadow:0 2px 8px rgba(35,42,70,.03)}.category-editor{margin-bottom:16px}.inner-editor,.item-editor{margin:12px 18px}.category-editor label,.item-editor label{display:grid;gap:9px;color:#2b344b;font-size:13px;font-weight:700}.category-editor input,.item-editor input{width:100%;height:40px;padding:9px 11px;border:1px solid #dce1eb;border-radius:7px;background:#fafbfe;outline:0}.category-editor input:focus,.item-editor input:focus{border-color:#786be2;box-shadow:0 0 0 3px #f0eeff}.item-title{margin:20px 0 10px}.item-input-row{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px;align-items:center}.category-editor footer,.item-editor footer{display:flex;justify-content:flex-end;margin-top:17px;padding-top:14px;border-top:1px solid #e7eaf1}.item-editor h3{font-size:16px}.toast-message{margin-bottom:12px}@media(max-width:720px){.template-toolbar,.group-header{align-items:flex-start;flex-direction:column}.toolbar-actions{width:100%}.template-item{grid-template-columns:30px 1fr}.template-item .text-link{grid-column:2;justify-self:start;padding:0}.group-actions{width:100%}.item-input-row{grid-template-columns:1fr}.template-root{padding:12px}.group-header,.template-item,.group-footer{padding-left:13px;padding-right:13px}.inner-editor,.item-editor{margin-left:12px;margin-right:12px}}
</style>
