import { queryString, request, requestBinary } from './client'
import type { Archive, CheckItem, CompanyFileReference, InitialFileReference, MyTask, Opinion, ResourceUploadResult, Reviewer, Task, TaskFile, TaskPage, TemplateCategory } from './types'

async function uploadResource(file: File, dirPath: string): Promise<ResourceUploadResult> {
  const form = new FormData()
  form.set('file', file, file.name); form.set('dirPath', dirPath)
  return request<ResourceUploadResult>('/files/upload', { method: 'POST', body: form })
}

async function uploadInitialFile(file: File): Promise<InitialFileReference> {
  const uploaded = await uploadResource(file, '/bms/pcb/task-drafts')
  return { fileId: uploaded.resourcePath, fileName: file.name, fileSize: file.size }
}

export const reviewApi = {
  listTasks: (query: Record<string, string | number | undefined>) => request<TaskPage>(`/tasks${queryString(query)}`),
  getTask: async (taskId: number) => {
    const page = await request<TaskPage>(`/tasks${queryString({ taskId, pageNo: 1, pageSize: 1 })}`)
    const task = page.items[0]
    if (!task) throw new Error('任务不存在或无权查看')
    return task
  },
  getMyTasks: () => request<MyTask[]>('/tasks/my'),
  uploadInitialFilesToCompany: (files: File[]) => Promise.all(files.map(uploadInitialFile)),
  saveTask: (body: Omit<Task, 'id' | 'status'> & { initialFiles?: InitialFileReference[] }, taskId?: number) => {
    return request<Task>(`/tasks/save${queryString({ taskId })}`, { method: 'POST', body: JSON.stringify(body) })
  },
  submitTask: (body: Omit<Task, 'id' | 'status'> & { initialFiles?: InitialFileReference[] }, taskId?: number) => {
    return request<Task>(`/tasks/submit${queryString({ taskId })}`, { method: 'POST', body: JSON.stringify(body) })
  },
  listOpinions: (taskId: number, filters: { severity?: string; status?: string; sourceType?: string; scene?: 'REVIEW_WORKSPACE' | 'DESIGNER_REPLY' } = {}) => request<Opinion[]>(`/tasks/${taskId}/opinions${queryString(filters)}`),
  raiseOpinion: (taskId: number, body: { sourceType: Opinion['sourceType']; sourceItemId?: number; content: string; richText?: string; severity?: string }) =>
    request<Opinion>(`/tasks/${taskId}/opinions`, { method: 'POST', body: JSON.stringify(body) }),
  replyOpinion: (opinionId: number, body: { replyType: 'ACCEPT' | 'REJECT'; reason?: string }) =>
    request<Opinion>(`/opinions/${opinionId}/reply`, { method: 'POST', body: JSON.stringify(body) }),
  confirmOpinion: (opinionId: number, body: { passed: boolean; comment?: string }) =>
    request<Opinion>(`/opinions/${opinionId}/confirm`, { method: 'POST', body: JSON.stringify(body) }),
  listCheckItems: (taskId: number) => request<CheckItem[]>(`/tasks/${taskId}/check-items`),
  submitCheckItem: (taskId: number, itemId: number, body: { result: string; comment?: string; richText?: string; linkedOpinionId?: number }) =>
    request<CheckItem>(`/tasks/${taskId}/check-items/${itemId}`, { method: 'PUT', body: JSON.stringify(body) }),
  submitCheckItems: (taskId: number, items: Array<{ itemId: number; result: string; comment?: string; richText?: string; attachmentFileIds?: number[] }>) =>
    request<CheckItem[]>(`/tasks/${taskId}/check-items/batch`, { method: 'PUT', body: JSON.stringify({ items }) }),
  listReviewers: (taskId: number, role: string) => request<Reviewer[]>(`/tasks/${taskId}/workflow/reviewers${queryString({ role })}`),
  submitNoOpinion: (taskId: number) => request<void>(`/tasks/${taskId}/workflow/reviewers/me/submit-no-opinion`, { method: 'POST' }),
  getTaskOptions: () => request<{ pcbTypes: string[]; reviewRoles: Array<{ code: string; name: string }> }>('/dictionaries/task-options'),
  listMockUsers: () => request<Array<{ id: number; displayName: string; email: string; departmentName: string; roles: string[] }>>('/mock-users'),
  async uploadTaskFile(taskId: number, category: 'PROCESS_REVIEW' | 'SCHEMATIC_REVIEW' | 'MUTUAL_CHECK_REVIEW', file: File, fileKind?: 'PROCESS' | 'STRUCTURE'): Promise<TaskFile> {
    const uploaded = await uploadResource(file, `/bms/pcb/tasks/${taskId}/${category.toLowerCase()}`)
    return request<TaskFile>(`/tasks/${taskId}/files`, { method: 'POST', body: JSON.stringify({
      category, companyFileId: uploaded.resourcePath, fileName: file.name, fileSize: file.size, fileKind
    }) })
  },
  latestFiles: (taskId: number, category: string) => request<TaskFile[]>(`/tasks/${taskId}/files/latest${queryString({ category })}`),
  downloadContent: (taskId: number, fileId: number) => requestBinary(`/tasks/${taskId}/files/${fileId}/content`),
  transition: (taskId: number, body: { action: string; comment?: string; assignedRole?: string; reviewerIds?: number[]; stageFiles?: Array<CompanyFileReference & { scene: 'PROCESS_REVIEW'; fileKind?: 'PROCESS' | 'STRUCTURE' }> }) =>
    request<{ taskId: number; fromStatus: string; toStatus: string; assignedReviewers: Reviewer[]; stageFiles: TaskFile[] }>(`/tasks/${taskId}/workflow/transitions`, { method: 'POST', body: JSON.stringify(body) }),
  getArchive: (taskId: number) => request<Archive>(`/tasks/${taskId}/archive`),
  importTemplate: (file: File) => {
    const data = new FormData()
    data.set('file', file)
    return request<{ totalRows: number; createdCount: number; updatedCount: number }>('/check-item-templates/import', { method: 'POST', body: data })
  },
  listTemplates: (reviewType?: string) => request<TemplateCategory[]>(`/check-item-templates${queryString({ reviewType })}`),
  createTemplate: (body: { reviewType: string; categoryName: string; items: Array<{ itemName: string }> }) =>
    request<TemplateCategory>('/check-item-templates', { method: 'POST', body: JSON.stringify(body) }),
  updateTemplate: (templateId: number, body: { categoryName: string; enabled: boolean; version: number; items: Array<{ id?: number; itemName: string; enabled: boolean; version?: number }> }) =>
    request<TemplateCategory>(`/check-item-templates/${templateId}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteTemplate: (id: number) => request<void>(`/check-item-templates/${id}`, { method: 'DELETE' })
}
