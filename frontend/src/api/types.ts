export type ReviewType = 'PCB' | 'SCHEMATIC'

export interface Task {
  id: number
  reviewType: ReviewType
  taskName: string
  projectName: string
  designerId: number
  designerName: string
  designName: string
  pcbType?: string
  expectedCompletedDate: string
  expertLeaderId: number
  expertLeaderName: string
  reviewRoles: string[]
  reviewDescription?: string
  status: string
}

/** 文件上传接口返回的 UUID；任务保存/提交时 files 仅传多个 UUID。 */
export type TaskFileReference = string

/** 文件上传接口返回的文件引用；用于阶段文件登记等非任务创建场景。 */
export interface FileReference {
  fileId: string
  fileName: string
  fileSize: number
  md5?: string
}

/** 公司资源服务上传代理的返回值。 */
export interface ResourceUploadResult {
  fileId: string
  taskFileId?: number
  fileName: string
  fileSize: number
  fileCategory: string
}

export interface TaskPage {
  total: number
  pageNo: number
  pageSize: number
  items: Task[]
}

export interface MyTask extends Task {
  actions: string[]
}

export interface Opinion {
  id: number
  taskId: number
  sourceType: 'EXPERT_REVIEW' | 'PROCESS_REVIEW' | 'STRUCTURE_REVIEW' | 'MUTUAL_CHECK_ITEM' | 'MUTUAL_EXTRA'
  sourceItemId?: number
  content: string
  richText?: string
  raisedBy: number
  raisedByName: string
  severity: 'SERIOUS' | 'GENERAL' | 'MINOR'
  createdAt?: string
  status: 'PENDING_REPLY' | 'PENDING_CONFIRMATION' | 'CONFIRMED_PASS' | 'CONFIRMED_REJECTED' | 'WITHDRAWN'
  replies: OpinionReply[]
}

export interface OpinionPage {
  total: number
  pageNo: number
  pageSize: number
  items: Opinion[]
}

export interface OpinionSummary {
  total: number
  pendingReply: number
  pendingConfirmation: number
  confirmedPass: number
  confirmedRejected: number
  withdrawn: number
}

export interface OpinionReply {
  id: number
  replyNo: number
  replyType: 'ACCEPT' | 'REJECT'
  reason?: string
  repliedBy: number
  repliedAt?: string
  confirmation?: OpinionConfirmation
}

export interface OpinionConfirmation {
  id: number
  passed: boolean
  comment?: string
  confirmedBy: number
  confirmedAt?: string
}

export interface CheckItem {
  id: number
  itemName: string
  sortNo: number
  result?: 'PASS' | 'FAIL' | 'NC'
  comment?: string
  richText?: string
  opinion?: { id: number; content: string; richText?: string; raisedBy: number; raisedByName: string; severity: string; status: string; createdAt?: string } | null
  status: string
}

export interface CheckItemCategory {
  category: {
    id: number
    reviewType: ReviewType
    itemName: string
    sortNo: number
  }
  items: CheckItemListItem[]
}

export interface CheckItemListItem {
  id: number
  itemName: string
  sortNo: number
  opinion?: {
    result: 'PASS' | 'FAIL' | 'NC'
    comment?: string
    richText?: string
  } | null
}

export interface Reviewer {
  id: number
  reviewerId: number
  role: string
  status: string
  noOpinion: boolean
}

/** 当前流程节点按职责归组的可分配人员；userId 可直接提交为 reviewerIds。 */
export interface AssignableReviewer {
  userId: number
  employeeNo: string
  displayName: string
  departmentName: string
  whitelistRole: string
}

export interface AssignableReviewerRole {
  reviewRole: string
  reviewers: AssignableReviewer[]
}

export interface ArchiveFile {
  fileId: number
  stageName: string
  fileCategory: string
  businessFileKey: string
  fileName: string
  fileFormat?: string
  uploaderId: number
  uploaderName: string
  uploadedAt?: string
  resourcePath?: string
  fileSize?: number
  md5?: string
  downloadPath: string
}

export interface TaskFile {
  id: number
  taskId: number
  category: string
  businessFileKey: string
  fileName: string
  fileFormat?: string
  fileSize: number
  md5: string
  resourcePath: string
  uploadedBy: number
  uploadedAt?: string
  uploadedStage?: string
  latest: boolean
}

export interface FlowNode {
  occurredAt?: string
  stageName: string
  operatorName: string
  content: string
}

export interface Archive {
  flowNodes: FlowNode[]
  stageFiles: ArchiveFile[]
}

export interface TemplateItem {
  id: number
  reviewType: ReviewType
  parentId?: number
  itemName: string
  sortNo: number
  enabled: boolean
  version: number
}

export interface TemplateCategory {
  category: TemplateItem
  items: TemplateItem[]
}

export interface TemplateListItem {
  id: number
  itemName: string
  sortNo: number
}

export interface TemplateListCategory {
  category: TemplateListItem
  items: TemplateListItem[]
}
