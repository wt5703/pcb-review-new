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

/** 公司资源服务上传完成后返回并随任务保存/提交请求传回 PCB 的初始文件引用。 */
export interface InitialFileReference {
  fileId: string
  fileName: string
  fileSize: number
  md5?: string
}

/** 公司资源服务文件引用；用于阶段文件登记等非任务创建场景。 */
export interface CompanyFileReference {
  companyFileId: string
  fileName: string
  fileSize: number
  md5?: string
}

/** 公司资源服务上传代理的返回值。 */
export interface ResourceUploadResult {
  resourceId: string
  resourcePath: string
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
  severity: 'SERIOUS' | 'GENERAL' | 'MINOR'
  createdAt?: string
  status: 'PENDING_REPLY' | 'PENDING_CONFIRMATION' | 'CONFIRMED_PASS' | 'CONFIRMED_REJECTED' | 'WITHDRAWN'
  replies: OpinionReply[]
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
  templateItemKey: string
  parentItemKey?: string
  categoryName?: string
  itemName: string
  sortNo: number
  result?: 'PASS' | 'FAIL' | 'NOT_APPLICABLE'
  comment?: string
  richText?: string
  linkedOpinionId?: number
  attachments: Array<{ fileId: number; sortNo: number; fileName: string; fileCategory: string; previewUrl?: string }>
  status: string
}

export interface Reviewer {
  id: number
  reviewerId: number
  role: string
  status: string
  noOpinion: boolean
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

export interface MailRecord {
  sentAt?: string
  scenario: string
  recipient: string
  status: string
  deliveryStatus: string
  failureReason?: string
}

export interface Archive {
  taskId: number
  finalStatus: string
  task: { taskId: number; taskName: string; status: string }
  stageFiles: ArchiveFile[]
  reviewers: Reviewer[]
  flowNodes: FlowNode[]
  mailRecords: MailRecord[]
}

export interface TemplateItem {
  id: number
  reviewType: ReviewType
  itemKey: string
  parentItemKey?: string
  itemName: string
  sortNo: number
  enabled: boolean
  version: number
}

export interface TemplateCategory {
  category: TemplateItem
  items: TemplateItem[]
}
