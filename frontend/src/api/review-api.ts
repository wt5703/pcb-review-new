import { queryString, request, requestBinary } from './client'
import type { Archive, AssignableReviewer, CheckItem, CheckItemCategory, TaskFileReference, MyTask, Opinion, OpinionPage, OpinionSummary, ResourceUploadResult, ReviewType, Task, TaskFile, TaskPage, TemplateCategory, TemplateExistence, TemplateList } from './types'

type ReviewerWhitelistRole = 'HARDWARE_EXPERT' | 'EMC_EXPERT' | 'STRUCTURE_EXPERT' | 'PROCESS_EXPERT' | 'PCB_EXPERT' | 'PCB_MUTUAL_CHECK' | 'SCHEMATIC_MUTUAL_CHECK'
type TaskSaveRequest = {
  reviewType: ReviewType
  taskName: string
  projectName: string
  designName: string
  pcbType?: string
  expectedCompletedDate: string
  expertLeaderEmployeeNo?: string
  reviewRoles: string[]
  reviewerAssignments: Array<{ reviewRole: string; reviewerEmployeeNos: string[] }>
  reviewDescription?: string
  files?: TaskFileReference[]
}

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
  listTasks: (query: { keyword?: string; reviewType?: string; statuses?: string[]; pageNo?: number; pageSize?: number }) =>
    request<TaskPage>(`/tasks${queryString({ ...query, statuses: query.statuses?.join(',') })}`),
  getTask: (taskId: number) => request<Task>(`/tasks/${taskId}`),
  getMyTasks: () => request<MyTask[]>('/tasks/my'),
  uploadInitialFilesToCompany: (files: File[], reviewType: ReviewType) => Promise.all(files.map((file) => uploadInitialFile(file, reviewType))),
  saveTask: (body: TaskSaveRequest, taskId?: number) => {
    return request<Task>(`/tasks/save${queryString({ taskId })}`, { method: 'POST', body: JSON.stringify(body) })
  },
  submitTask: (body: TaskSaveRequest, taskId?: number) => {
    return request<Task>(`/tasks/submit${queryString({ taskId })}`, { method: 'POST', body: JSON.stringify(body) })
  },
  listOpinions: (taskId: number, filters: { severity?: string; status?: string; sourceType?: string; sourceTypes?: string; scene?: 'REVIEW_WORKSPACE' | 'DESIGNER_REPLY'; pageNo?: number; pageSize?: number } = {}) => request<OpinionPage>(`/tasks/${taskId}/opinions${queryString(filters)}`),
  getOpinionSummary: (taskId: number, sourceTypes?: string) => request<OpinionSummary>(`/tasks/${taskId}/opinions/summary${queryString({ sourceTypes })}`),
  raiseOpinion: (taskId: number, body: { sourceType: Opinion['sourceType']; sourceItemId?: number; comment: string; richText?: string; severity?: string }) =>
    request<Opinion>(`/tasks/${taskId}/opinions`, { method: 'POST', body: JSON.stringify(body) }),
  submitNoOpinion: (taskId: number, sourceType: 'PCB_REVIEW' | 'SCHEMATIC_REVIEW' | 'PCB_PROCESS_REVIEW' | 'PCB_STRUCTURE_REVIEW') =>
    request<Opinion>(`/tasks/${taskId}/opinions/no-opinion`, { method: 'POST', body: JSON.stringify({ sourceType }) }),
  updateOpinion: (opinionId: number, body: { comment: string; richText?: string; severity?: string }) =>
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
    request<AssignableReviewer[]>(`/workflow/assignable-reviewers${queryString({ reviewType, taskStatus })}`),
  getTaskOptions: () => request<{ pcbTypes: string[]; reviewRoles: Array<{ code: string; name: string }>; reviewerWhitelistRoles: Array<{ code: string; name: string }>; taskStatuses: Array<{ code: string; name: string }> }>('/dictionaries/task-options'),
  uploadTaskFile: (taskId: number, fileCategory: string, file: File) => uploadResource(file, fileCategory, taskId),
  latestFiles: (taskId: number, fileCategory: string) => request<TaskFile[]>(`/files/latest${queryString({ taskId, fileCategory })}`),
  downloadContent: (fileId: number) => requestBinary(`/files/download${queryString({ fileId })}`, { method: 'POST' }),
  transition: (taskId: number, body: { actions: string[]; comment?: string; reviewerEmployeeNos?: string[] }) =>
    request<{ taskId: number; fromStatus: string; toStatus: string; assignedReviewerIds: number[] }>(`/tasks/${taskId}/workflow/transitions`, { method: 'POST', body: JSON.stringify(body) }),
  getArchive: (taskId: number) => request<Archive>(`/tasks/${taskId}/archive`),
  exportArchiveOpinions: (taskId: number) => requestBinary(`/tasks/${taskId}/archive/export`),
  importTemplate: (file: File, reviewType: ReviewType, confirmed = false) => {
    const data = new FormData()
    data.set('file', file)
    data.set('reviewType', reviewType)
    data.set('confirmed', String(confirmed))
    return request<{ totalRows: number; createdCount: number; replacedCount: number }>('/check-item-templates/import', { method: 'POST', body: data })
  },
  getTemplate: (reviewType: ReviewType) => request<TemplateList>(`/check-item-templates${queryString({ reviewType })}`),
  checkTemplateExistence: (reviewType: ReviewType) => request<TemplateExistence>(`/check-item-templates/existence${queryString({ reviewType })}`),
  createTemplate: (body: { reviewType: string; categoryName: string; items?: Array<{ itemName: string }> }) =>
    request<TemplateCategory>('/check-item-templates', { method: 'POST', body: JSON.stringify(body) }),
  updateTemplate: (body: { itemId: number; itemName: string; items?: Array<{ itemId?: number; itemName: string }> }) =>
    request<{ item: { id: number; itemName: string }; items: Array<{ id: number; itemName: string }> }>('/check-item-templates', { method: 'PUT', body: JSON.stringify(body) }),
  addReviewerWhitelist: (roleEmployeeNos: Array<{ reviewRole: ReviewerWhitelistRole; employeeNos: string[] }>) =>
    request<{ createdCount: number; roleEmployeeNos: Array<{ reviewRole: string; employeeNos: string[] }> }>('/reviewer-whitelists', {
      method: 'POST', body: JSON.stringify({ roleEmployeeNos })
    }),
  listReviewerWhitelists: (query: { keyword?: string; pageNo?: number; pageSize?: number } = {}) =>
    request<{ total: number; pageNo: number; pageSize: number; items: Array<{ id: number; reviewRole: ReviewerWhitelistRole; employeeNo: string; displayName?: string; email?: string; mobile?: string; departmentName?: string; createdAt?: string }> }>('/reviewer-whitelists/query', {
      method: 'POST', body: JSON.stringify(query)
    }),
  removeReviewerWhitelist: (params: { id?: number; employeeNo?: string }) =>
    request<{ deletedCount: number }>(`/reviewer-whitelists${queryString(params)}`, { method: 'DELETE' }),
  disableCheckItemTemplate: (id: number, category: 'CATEGORY' | 'ITEM') =>
    request<void>('/check-item-templates/' + id + queryString({ category }), { method: 'DELETE' })
  }
