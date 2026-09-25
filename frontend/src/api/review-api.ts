import { queryString, request, requestBinary } from './client'
import type { Archive, AssignableReviewerRole, CheckItem, CheckItemCategory, TaskFileReference, MyTask, Opinion, OpinionPage, OpinionSummary, ResourceUploadResult, Reviewer, ReviewType, Task, TaskFile, TaskPage, TemplateCategory, TemplateListCategory } from './types'

type ReviewerWhitelistRole = 'HARDWARE_EXPERT' | 'EMC_EXPERT' | 'STRUCTURE_EXPERT' | 'PROCESS_EXPERT' | 'PCB_EXPERT' | 'PCB_MUTUAL_CHECK' | 'SCHEMATIC_MUTUAL_CHECK'

async function uploadResource(file: File, fileCategory: string, taskId?: number): Promise<ResourceUploadResult> {
  const form = new FormData()
  form.set('file', file, file.name); form.set('fileCategory', fileCategory)
  if (taskId) form.set('taskId', String(taskId))
  return request<ResourceUploadResult>('/files/upload', { method: 'POST', body: form })
}

async function uploadInitialFile(file: File, reviewType: ReviewType): Promise<TaskFileReference> {
  const uploaded = await uploadResource(file, reviewType === 'PCB' ? 'PCB_REVIEW' : 'SCHEMATIC_REVIEW')
  return uploaded.fileId
}

export const reviewApi = {
  listTasks: (query: Record<string, string | number | undefined>) => request<TaskPage>(`/tasks${queryString(query)}`),
  getTask: (taskId: number) => request<Task>(`/tasks/${taskId}`),
  getMyTasks: () => request<MyTask[]>('/tasks/my'),
  uploadInitialFilesToCompany: (files: File[], reviewType: ReviewType) => Promise.all(files.map((file) => uploadInitialFile(file, reviewType))),
  saveTask: (body: Omit<Task, 'id' | 'status'> & { files?: TaskFileReference[] }, taskId?: number) => {
    return request<Task>(`/tasks/save${queryString({ taskId })}`, { method: 'POST', body: JSON.stringify(body) })
  },
  submitTask: (body: Omit<Task, 'id' | 'status'> & { files?: TaskFileReference[] }, taskId?: number) => {
    return request<Task>(`/tasks/submit${queryString({ taskId })}`, { method: 'POST', body: JSON.stringify(body) })
  },
  listOpinions: (taskId: number, filters: { severity?: string; status?: string; sourceType?: string; sourceTypes?: string; scene?: 'REVIEW_WORKSPACE' | 'DESIGNER_REPLY'; pageNo?: number; pageSize?: number } = {}) => request<OpinionPage>(`/tasks/${taskId}/opinions${queryString(filters)}`),
  getOpinionSummary: (taskId: number, sourceTypes?: string) => request<OpinionSummary>(`/tasks/${taskId}/opinions/summary${queryString({ sourceTypes })}`),
  raiseOpinion: (taskId: number, body: { sourceType: Opinion['sourceType']; sourceItemId?: number; content: string; richText?: string; severity?: string }) =>
    request<Opinion>(`/tasks/${taskId}/opinions`, { method: 'POST', body: JSON.stringify(body) }),
  submitNoOpinion: (taskId: number, sourceType: 'EXPERT_REVIEW' | 'PROCESS_REVIEW' | 'STRUCTURE_REVIEW') =>
    request<Opinion>(`/tasks/${taskId}/opinions/no-opinion`, { method: 'POST', body: JSON.stringify({ sourceType }) }),
  updateOpinion: (opinionId: number, body: { content: string; richText?: string; severity?: string }) =>
    request<Opinion>(`/opinions/${opinionId}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteOpinion: (opinionId: number) => request<void>(`/opinions/${opinionId}`, { method: 'DELETE' }),
  replyOpinion: (opinionId: number, body: { replyType: 'ACCEPT' | 'REJECT'; reason?: string }) =>
    request<Opinion>(`/opinions/${opinionId}/reply`, { method: 'POST', body: JSON.stringify(body) }),
  confirmOpinion: (opinionId: number, body: { passed: boolean; comment?: string }) =>
    request<Opinion>(`/opinions/${opinionId}/confirm`, { method: 'POST', body: JSON.stringify(body) }),
  listCheckItems: (taskId: number) => request<CheckItemCategory[]>(`/tasks/${taskId}/check-items`),
  submitCheckItem: (taskId: number, itemId: number, body: { result: string; comment?: string; richText?: string }) =>
    request<CheckItem>(`/tasks/${taskId}/check-items/${itemId}`, { method: 'PUT', body: JSON.stringify(body) }),
  submitCheckItems: (taskId: number, items: Array<{ itemId: number; result: string; comment?: string; richText?: string }>) =>
    request<CheckItem[]>(`/tasks/${taskId}/check-items/batch`, { method: 'PUT', body: JSON.stringify({ items }) }),
  listAssignableReviewers: (reviewType: 'PCB' | 'SCHEMATIC', taskStatus: string) =>
    request<AssignableReviewerRole[]>(`/workflow/assignable-reviewers${queryString({ reviewType, taskStatus })}`),
  getTaskOptions: () => request<{ pcbTypes: string[]; reviewRoles: Array<{ code: string; name: string }>; reviewerWhitelistRoles: Array<{ code: string; name: string }>; taskStatuses: Array<{ code: string; name: string }> }>('/dictionaries/task-options'),
  listMockUsers: () => request<Array<{ id: number; employeeNo: string; displayName: string; email: string; mobile?: string; departmentName: string; roles: string[] }>>('/mock-users'),
  uploadTaskFile: (taskId: number, fileCategory: string, file: File) => uploadResource(file, fileCategory, taskId),
  latestFiles: (taskId: number, fileCategory: string) => request<TaskFile[]>(`/files/latest${queryString({ taskId, fileCategory })}`),
  downloadContent: (taskId: number, fileId: number) => requestBinary(`/files/download${queryString({ taskId, fileId })}`, { method: 'POST' }),
  transition: (taskId: number, body: { actions: string[]; comment?: string; assignedRole?: string; reviewerIds?: number[] }) =>
    request<{ taskId: number; fromStatus: string; toStatus: string; assignedReviewers: Reviewer[] }>(`/tasks/${taskId}/workflow/transitions`, { method: 'POST', body: JSON.stringify(body) }),
  getArchive: (taskId: number) => request<Archive>(`/tasks/${taskId}/archive`),
  importTemplate: (file: File, reviewType: ReviewType) => {
    const data = new FormData()
    data.set('file', file)
    data.set('reviewType', reviewType)
    return request<{ totalRows: number; createdCount: number; updatedCount: number }>('/check-item-templates/import', { method: 'POST', body: data })
  },
  listTemplates: (reviewType?: ReviewType) => request<TemplateListCategory[]>(`/check-item-templates${queryString({ reviewType })}`),
  createTemplate: (body: { reviewType: string; categoryName: string; items?: Array<{ itemName: string }> }) =>
    request<TemplateCategory>('/check-item-templates', { method: 'POST', body: JSON.stringify(body) }),
  updateTemplate: (body: { itemId: number; itemName: string; items?: Array<{ itemId?: number; itemName: string }> }) =>
    request<{ item: { id: number; itemName: string }; items: Array<{ id: number; itemName: string }> }>('/check-item-templates', { method: 'PUT', body: JSON.stringify(body) }),
  addReviewerWhitelist: (roleEmployeeNos: Array<{ reviewRole: ReviewerWhitelistRole; employeeNos: string[] }>) =>
    request<{ createdCount: number; roleEmployeeNos: Array<{ reviewRole: string; employeeNos: string[] }> }>('/reviewer-whitelists', {
      method: 'POST', body: JSON.stringify({ roleEmployeeNos })
    }),
  listReviewerWhitelists: () => request<Array<{ id: number; reviewRole: ReviewerWhitelistRole; employeeNo: string; displayName?: string; email?: string; mobile?: string; createdAt?: string }>>('/reviewer-whitelists'),
  removeReviewerWhitelist: (params: { id?: number; employeeNo?: string }) =>
    request<{ deletedCount: number }>(`/reviewer-whitelists${queryString(params)}`, { method: 'DELETE' }),
  disableCheckItemTemplate: (id: number, category: 'CATEGORY' | 'ITEM') =>
    request<void>('/check-item-templates/' + id + queryString({ category }), { method: 'DELETE' })
  }
