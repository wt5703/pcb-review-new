<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { reviewApi } from '@/api/review-api'
import { identity } from '@/stores/identity'

const router = useRouter()
const form = reactive({ reviewType: 'PCB', taskName: '', projectName: '', designName: '', pcbType: '', submitAfterCreate: true })
const sourceFile = ref<File | null>(null)
const saving = ref(false)
const error = ref('')

function selectFile(event: Event): void { sourceFile.value = (event.target as HTMLInputElement).files?.[0] ?? null }
async function digest(file: File): Promise<string> {
  const buffer = await file.arrayBuffer()
  const hash = await crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(hash)).map((item) => item.toString(16).padStart(2, '0')).join('')
}
async function save(): Promise<void> {
  error.value = ''
  if (!form.taskName || !form.projectName || !form.designName) { error.value = '请填写任务名称、项目名称和设计名称。'; return }
  saving.value = true
  try {
    const task = await reviewApi.createTask({ reviewType: form.reviewType as 'PCB' | 'SCHEMATIC', taskName: form.taskName, projectName: form.projectName, designerId: identity.userId, designName: form.designName, pcbType: form.pcbType || undefined })
    const fileIds: number[] = []
    if (sourceFile.value) {
      const session = await reviewApi.createUploadSession(task.id, 'PCB_SCHEMATIC')
      const registered = await reviewApi.registerFile(task.id, { uploadSessionId: session.uploadSessionId, category: 'PCB_SCHEMATIC', businessFileKey: `${form.reviewType}_SOURCE`, fileName: sourceFile.value.name, fileSize: sourceFile.value.size, md5: await digest(sourceFile.value) })
      fileIds.push(registered.id)
    }
    if (form.submitAfterCreate) await reviewApi.submitTask(task.id, fileIds)
    await router.push(`/tasks/${task.id}`)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '创建失败' } finally { saving.value = false }
}
</script>

<template>
  <div class="page-head"><div><h1>创建评审任务</h1><p>任务创建人默认为当前 Mock 用户 #{{ identity.userId }}。</p></div></div>
  <form class="card form-card" @submit.prevent="save"><div class="notice">文件服务目前为本地 Mock：选择文件后会登记元数据与 MD5，不会将二进制文件上传至公司文件服务。</div>
    <div class="form-grid"><label>评审类型 *<select v-model="form.reviewType"><option value="PCB">PCB 评审</option><option value="SCHEMATIC">原理图评审</option></select></label><label>任务名称 *<input v-model.trim="form.taskName" placeholder="例如：BMU 控制板 V1.0" /></label><label>项目名称 *<input v-model.trim="form.projectName" placeholder="例如：BMS 平台项目" /></label><label>设计名称 *<input v-model.trim="form.designName" placeholder="例如：BMU_Control_Board" /></label><label>PCB 类型<input v-model.trim="form.pcbType" placeholder="例如：4 层板" /></label><label>设计文件（可选）<input type="file" @change="selectFile" /><small v-if="sourceFile">{{ sourceFile.name }} · {{ Math.ceil(sourceFile.size / 1024) }} KB</small></label></div>
    <label class="checkbox"><input v-model="form.submitAfterCreate" type="checkbox" /> 创建后立即提交，进入评审流程</label><p v-if="error" class="form-error">{{ error }}</p><footer class="form-footer"><button class="btn" type="button" @click="router.back()">取消</button><button class="btn primary" :disabled="saving" type="submit">{{ saving ? '正在保存…' : '创建任务' }}</button></footer>
  </form>
</template>
