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
  version: number
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

export interface OpinionReply {
  id: number
  opinionId: number
  replyNo: number
  replyType: 'ACCEPT' | 'REJECT'
  reason?: string
  fileVersionId?: number
  repliedBy: number
  repliedAt?: string
}

export interface OpinionConfirmation {
  id: number
  replyId: number
  passed: boolean
  comment?: string
  confirmedBy: number
  confirmedAt?: string
}

export interface Opinion {
  id: number
  taskId: number
  sourceType: 'EXPERT_REVIEW' | 'PROCESS_REVIEW' | 'STRUCTURE_REVIEW' | 'MUTUAL_CHECK_ITEM' | 'MUTUAL_EXTRA'
  sourceItemId?: number
  content: string
  raisedBy: number
  fileVersionId?: number
  imageUrl?: string
  status: 'PENDING_REPLY' | 'PENDING_CONFIRMATION' | 'CONFIRMED_PASS' | 'CONFIRMED_REJECTED' | 'WITHDRAWN'
  version: number
  designerReplies: OpinionReply[]
  confirmations: OpinionConfirmation[]
  attachments: Array<{ fileId: number; sortNo: number; fileName: string; fileCategory: string; previewUrl?: string }>
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
  linkedOpinionId?: number
  attachments: Array<{ fileId: number; sortNo: number; fileName: string; fileCategory: string; previewUrl?: string }>
  status: string
  version: number
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
  versionNo: number
  uploaderId: number
  uploaderName: string
  uploadedAt?: string
  downloadPath: string
}

export interface FlowOpinion {
  occurredAt?: string
  stageName: string
  operatorId: number
  operatorName: string
  content: string
  source: string
  sourceId: number
  designerReplies: Array<{ replyNo: number; replyType: string; content?: string; repliedAt?: string }>
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
  flowOpinions: FlowOpinion[]
  flowEvents: Array<{ occurredAt?: string; stageName: string; action: string; operatorName: string; comment?: string }>
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
