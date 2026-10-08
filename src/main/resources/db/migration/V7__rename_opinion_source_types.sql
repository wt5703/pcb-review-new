-- 统一 PCB 意见来源名称，避免 EXPERT/PROCESS/STRUCTURE 的歧义；保留原理图及互检来源不变。
UPDATE review_opinion SET source_type = 'PCB_REVIEW' WHERE source_type = 'EXPERT_REVIEW';
UPDATE review_opinion SET source_type = 'PCB_PROCESS_REVIEW' WHERE source_type = 'PROCESS_REVIEW';
UPDATE review_opinion SET source_type = 'PCB_STRUCTURE_REVIEW' WHERE source_type = 'STRUCTURE_REVIEW';
