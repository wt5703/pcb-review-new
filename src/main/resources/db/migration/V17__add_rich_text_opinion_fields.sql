-- 专家评审和互检单提取意见均按前端富文本字符串原样保存；图片可作为 data URI 内嵌于字符串。
ALTER TABLE review_opinion ADD COLUMN rich_text_content LONGTEXT;
UPDATE review_opinion SET rich_text_content = content WHERE rich_text_content IS NULL;

-- 保留互检单页面本身的回显快照，关联意见仍用于后续答复和确认闭环。
ALTER TABLE task_check_item ADD COLUMN opinion_rich_text LONGTEXT;
