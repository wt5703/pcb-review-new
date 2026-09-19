import { queryString, request } from './client'
import type { Archive, CheckItem, MyTask, Opinion, Reviewer, Task, TaskPage } from './types'

export const reviewApi = {
  listTasks: (query: Record<string, string | number | undefined>) => request<TaskPage>(`/tasks${queryString(query)}`),
  getTask: (taskId: number) => request<Task>(`/tasks/${taskId}`),
  getMyTasks: () => request<MyTask[]>('/tasks/my'),
  createTask: (body: Omit<Task, 'id' | 'status'>) => request<Task>('/tasks', { method: 'POST', body: JSON.stringify(body) }),
  submitTask: (taskId: number, initialFileIds: number[]) => request<Task>(`/tasks/${taskId}/submit`, { method: 'POST', body: JSON.stringify({ initialFileIds }) }),
  listOpinions: (taskId: number) => request<Opinion[]>(`/tasks/${taskId}/opinions`),
  raiseOpinion: (taskId: number, body: { sourceType: Opinion['sourceType']; sourceItemId?: number; content: string; fileVersionId?: number }) =>
    request<Opinion>(`/tasks/${taskId}/opinions`, { method: 'POST', body: JSON.stringify(body) }),
  replyOpinion: (opinionId: number, body: { replyType: 'ACCEPT' | 'REJECT'; reason?: string; fileVersionId?: number }) =>
    request<Opinion>(`/opinions/${opinionId}/replies`, { method: 'POST', body: JSON.stringify(body) }),
  confirmOpinion: (opinionId: number, body: { passed: boolean; comment?: string }) =>
    request<Opinion>(`/opinions/${opinionId}/confirmations`, { method: 'POST', body: JSON.stringify(body) }),
  listCheckItems: (taskId: number) => request<CheckItem[]>(`/tasks/${taskId}/check-items`),
  submitCheckItem: (taskId: number, itemId: number, body: { result: string; comment?: string; linkedOpinionId?: number; version: number }) =>
    request<CheckItem>(`/tasks/${taskId}/check-items/${itemId}`, { method: 'PUT', body: JSON.stringify(body) }),
  listReviewers: (taskId: number, role: string) => request<Reviewer[]>(`/tasks/${taskId}/reviewers${queryString({ role })}`),
  assignReviewers: (taskId: number, role: string, reviewerIds: number[], reassign = false) =>
    request<Reviewer[]>(`/tasks/${taskId}/reviewers`, { method: reassign ? 'PUT' : 'POST', body: JSON.stringify({ role, reviewerIds }) }),
  submitNoOpinion: (taskId: number) => request<void>(`/tasks/${taskId}/reviewers/me/no-opinion`, { method: 'POST' }),
  createUploadSession: (taskId: number, category: string) =>
    request<{ uploadSessionId: string; uploadUrl: string }>(`/tasks/${taskId}/files/upload-sessions`, { method: 'POST', body: JSON.stringify({ category }) }),
  registerFile: (taskId: number, body: { uploadSessionId: string; category: string; businessFileKey: string; fileName: string; fileSize: number; md5: string }) =>
    request<{ id: number; versionNo: number }>(`/tasks/${taskId}/files`, { method: 'POST', body: JSON.stringify(body) }),
  requestDownload: (fileId: number) => request<{ fileId: number; downloadUrl: string }>(`/files/${fileId}/download`),
  transition: (taskId: number, body: { action: string; version: number; comment?: string }) =>
    request<Task>(`/tasks/${taskId}/workflow/transitions`, { method: 'POST', body: JSON.stringify(body) }),
  getArchive: (taskId: number) => request<Archive>(`/tasks/${taskId}/archive`),
  importTemplate: (file: File) => {
    const data = new FormData()
    data.set('file', file)
    return request<{ totalRows: number; createdCount: number; updatedCount: number }>('/check-item-templates/import', { method: 'POST', body: data })
  },
  createTemplate: (body: { reviewType: string; itemKey: string; parentItemKey?: string; itemName: string; sortNo: number }) =>
    request<{ id: number }>('/check-item-templates', { method: 'POST', body: JSON.stringify(body) })
}
