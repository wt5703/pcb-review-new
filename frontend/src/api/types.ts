export type ReviewType = 'PCB' | 'SCHEMATIC'

export interface Task {
  id: number
  reviewType: ReviewType
  taskName: string
  projectName: string
  designerId: number
  designName: string
  pcbType?: string
  status: string
}

export interface TaskPage {
  total: number
  pageNo: number
  pageSize: number
  items: Task[]
}

export interface MyTask extends Pick<Task, 'id' | 'taskName' | 'reviewType' | 'status'> {
  actions: string[]
}

export interface OpinionReply {
  id: number
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
  status: 'PENDING_REPLY' | 'PENDING_CONFIRMATION' | 'CONFIRMED_PASS' | 'WITHDRAWN'
  version: number
  designerReplies: OpinionReply[]
  confirmations: OpinionConfirmation[]
}

export interface CheckItem {
  id: number
  templateItemKey: string
  parentItemKey?: string
  itemName: string
  sortNo: number
  result?: 'PASS' | 'FAIL' | 'NOT_APPLICABLE'
  comment?: string
  linkedOpinionId?: number
  status: string
  version: number
}

export interface Reviewer {
  reviewerId: number
  reviewRole: string
  processStatus: string
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
