import { queryString, request } from './client'
import type { Archive, CheckItem, MyTask, Opinion, Reviewer, Task, TaskPage, TemplateCategory } from './types'

export const reviewApi = {
  listTasks: (query: Record<string, string | number | undefined>) => request<TaskPage>(`/tasks${queryString(query)}`),
  getTask: (taskId: number) => request<Task>(`/tasks/${taskId}`),
  getMyTasks: () => request<MyTask[]>('/tasks/my'),
  createTask: (body: Omit<Task, 'id' | 'status' | 'version'>, files: File[], submit: boolean) => {
    const form = new FormData()
    form.set('task', new Blob([JSON.stringify(body)], { type: 'application/json' }))
    files.forEach((file) => form.append('files', file, file.name))
    form.set('submit', String(submit))
    return request<Task>('/tasks', { method: 'POST', body: form })
  },
  submitTask: (taskId: number, initialFileIds: number[]) => request<Task>(`/tasks/${taskId}/submit`, { method: 'POST', body: JSON.stringify({ initialFileIds }) }),
  saveDraftFiles: (taskId: number, initialFileIds: number[]) => request<Task>(`/tasks/${taskId}/draft-files`, { method: 'POST', body: JSON.stringify({ initialFileIds }) }),
  listOpinions: (taskId: number, filters: { severity?: string; status?: string } = {}) => request<Opinion[]>(`/tasks/${taskId}/opinions${queryString(filters)}`),
  raiseOpinion: (taskId: number, body: { sourceType: Opinion['sourceType']; sourceItemId?: number; content: string; fileVersionId?: number; severity?: string; imageUrl?: string; attachmentFileIds?: number[] }) =>
    request<Opinion>(`/tasks/${taskId}/opinions`, { method: 'POST', body: JSON.stringify(body) }),
  replyOpinion: (opinionId: number, body: { replyType: 'ACCEPT' | 'REJECT'; reason?: string; fileVersionId?: number }) =>
    request<Opinion>(`/opinions/${opinionId}/reply`, { method: 'POST', body: JSON.stringify(body) }),
  confirmOpinion: (opinionId: number, body: { passed: boolean; comment?: string }) =>
    request<Opinion>(`/opinions/${opinionId}/confirm`, { method: 'POST', body: JSON.stringify(body) }),
  listCheckItems: (taskId: number) => request<CheckItem[]>(`/tasks/${taskId}/check-items`),
  submitCheckItem: (taskId: number, itemId: number, body: { result: string; comment?: string; linkedOpinionId?: number; version: number }) =>
    request<CheckItem>(`/tasks/${taskId}/check-items/${itemId}`, { method: 'PUT', body: JSON.stringify(body) }),
  submitCheckItems: (taskId: number, items: Array<{ itemId: number; result: string; comment?: string; attachmentFileIds: number[]; version: number }>) =>
    request<CheckItem[]>(`/tasks/${taskId}/check-items/batch`, { method: 'PUT', body: JSON.stringify({ items }) }),
  listReviewers: (taskId: number, role: string) => request<Reviewer[]>(`/tasks/${taskId}/reviewers${queryString({ role })}`),
  assignReviewers: (taskId: number, role: string, reviewerIds: number[], reassign = false) =>
    request<Reviewer[]>(`/tasks/${taskId}/reviewers`, { method: reassign ? 'PUT' : 'POST', body: JSON.stringify({ role, reviewerIds }) }),
  submitNoOpinion: (taskId: number) => request<void>(`/tasks/${taskId}/reviewers/me/submit-no-opinion`, { method: 'POST' }),
  getTaskOptions: () => request<{ pcbTypes: string[]; reviewRoles: Array<{ code: string; name: string }> }>('/dictionaries/task-options'),
  listMockUsers: () => request<Array<{ id: number; displayName: string; email: string; departmentName: string; roles: string[] }>>('/mock-users'),
  createUploadSession: (taskId: number, category: string) =>
    request<{ uploadSessionId: string; uploadUrl: string }>(`/tasks/${taskId}/files/upload-sessions`, { method: 'POST', body: JSON.stringify({ category }) }),
  registerFile: (taskId: number, body: { uploadSessionId: string; category: string; businessFileKey: string; fileName: string; fileSize: number; md5: string }) =>
    request<{ id: number; versionNo: number }>(`/tasks/${taskId}/files`, { method: 'POST', body: JSON.stringify(body) }),
  requestDownload: (fileId: number) => request<{ fileId: number; downloadUrl: string }>(`/files/${fileId}/download`),
  transition: (taskId: number, body: { action: string; version: number; comment?: string }) =>
    request<{ taskId: number; fromStatus: string; toStatus: string; version: number }>(`/tasks/${taskId}/workflow/transitions`, { method: 'POST', body: JSON.stringify(body) }),
  getArchive: (taskId: number) => request<Archive>(`/tasks/${taskId}/archive`),
  importTemplate: (file: File) => {
    const data = new FormData()
    data.set('file', file)
    return request<{ totalRows: number; createdCount: number; updatedCount: number }>('/check-item-templates/import', { method: 'POST', body: data })
  },
  listTemplates: (reviewType?: string) => request<TemplateCategory[]>(`/check-item-templates${queryString({ reviewType })}`),
  createTemplate: (body: { reviewType: string; categoryKey: string; categoryName: string; sortNo: number; items: Array<{ itemKey: string; itemName: string; sortNo: number; enabled: boolean }> }) =>
    request<TemplateCategory>('/check-item-templates', { method: 'POST', body: JSON.stringify(body) }),
  updateTemplate: (templateId: number, body: { categoryKey: string; categoryName: string; sortNo: number; enabled: boolean; version: number; items: Array<{ id?: number; itemKey: string; itemName: string; sortNo: number; enabled: boolean; version?: number }> }) =>
    request<TemplateCategory>(`/check-item-templates/${templateId}`, { method: 'PUT', body: JSON.stringify(body) }),
  disableTemplate: (templateId: number, version: number) => request<void>(`/check-item-templates/${templateId}${queryString({ version })}`, { method: 'DELETE' })
}
