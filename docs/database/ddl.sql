-- PCB / 原理图评审平台 DDL。请先执行本文件，再执行 init.sql。
-- 每张表的索引均内嵌在对应 CREATE TABLE 语句中，避免将建表和索引拆成独立 SQL 块。

CREATE TABLE review_task (
    id BIGINT PRIMARY KEY,
    review_type VARCHAR(32) NOT NULL,
    task_name VARCHAR(200) NOT NULL,
    project_name VARCHAR(200) NOT NULL,
    designer_employee_no VARCHAR(64) NOT NULL,
    designer_name VARCHAR(100) NOT NULL DEFAULT '',
    design_name VARCHAR(200) NOT NULL,
    pcb_type VARCHAR(64),
    status VARCHAR(64) NOT NULL,
    initial_file_ids VARCHAR(1000) NOT NULL,
    expected_completed_date DATE,
    expert_leader_employee_no VARCHAR(64),
    expert_leader_name VARCHAR(100),
    review_roles VARCHAR(500) NOT NULL DEFAULT '',
    reviewer_assignments VARCHAR(4000) NOT NULL DEFAULT '',
    assigned_reviewer_employee_nos VARCHAR(2000) NOT NULL DEFAULT '',
    review_description VARCHAR(2000),
    version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_review_task_query (review_type, status, designer_employee_no),
    INDEX idx_review_task_designer_name (designer_name)
);

CREATE TABLE review_opinion (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_item_id BIGINT,
    severity VARCHAR(32) NOT NULL,
    is_no_opinion BOOLEAN NOT NULL DEFAULT FALSE,
    comment LONGTEXT NOT NULL,
    rich_text_content LONGTEXT,
    raised_by_employee_no VARCHAR(64) NOT NULL,
    raised_by_name VARCHAR(100) NOT NULL,
    status VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_review_opinion_task_status (task_id, status, raised_by_employee_no)
);

CREATE TABLE review_file (
    id BIGINT PRIMARY KEY,
    file_id VARCHAR(64) NOT NULL UNIQUE,
    task_id BIGINT,
    file_category VARCHAR(32) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    file_format VARCHAR(32) NOT NULL,
    file_size BIGINT NOT NULL,
    md5 VARCHAR(64) NOT NULL,
    resource_path VARCHAR(1024) NOT NULL,
    is_latest BOOLEAN NOT NULL,
    uploaded_stage VARCHAR(64),
    uploaded_by_employee_no VARCHAR(64) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_review_file_latest (task_id, file_category, is_latest),
    INDEX idx_review_file_owner (uploaded_by_employee_no, uploaded_at)
);

CREATE TABLE opinion_reply (
    id BIGINT PRIMARY KEY,
    opinion_id BIGINT NOT NULL,
    reply_type VARCHAR(32) NOT NULL,
    reason VARCHAR(2000),
    replied_by_employee_no VARCHAR(64) NOT NULL,
    reply_no INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (opinion_id, reply_no),
    INDEX idx_opinion_reply_opinion (opinion_id, reply_no)
);

CREATE TABLE opinion_confirmation (
    id BIGINT PRIMARY KEY,
    opinion_id BIGINT NOT NULL,
    reply_id BIGINT NOT NULL,
    passed BOOLEAN NOT NULL,
    comment VARCHAR(2000),
    confirmed_by_employee_no VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_opinion_confirmation_opinion (opinion_id, created_at)
);

CREATE TABLE check_item_template (
    id BIGINT PRIMARY KEY,
    review_type VARCHAR(32) NOT NULL,
    parent_id BIGINT,
    item_name VARCHAR(500) NOT NULL,
    item_rich_text MEDIUMTEXT,
    sort_no INTEGER NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_check_template_type_enabled (review_type, enabled, sort_no),
    INDEX idx_check_template_parent (parent_id, sort_no)
);

CREATE TABLE task_check_item (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    parent_id BIGINT,
    sort_no INTEGER NOT NULL DEFAULT 0,
    check_result VARCHAR(32),
    comment VARCHAR(2000),
    rich_text LONGTEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, item_id),
    INDEX idx_task_check_item_task (task_id, sort_no),
    INDEX idx_task_check_item_parent (task_id, parent_id, sort_no)
);

CREATE TABLE task_flow_record (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    action_name VARCHAR(100) NOT NULL,
    operate_employee_no VARCHAR(64) NOT NULL,
    comment VARCHAR(2000),
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_flow_record_task (task_id, create_at)
);

CREATE TABLE outbox_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    INDEX idx_outbox_event_status_created (status, created_at)
);

CREATE TABLE notification_send_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    outbox_event_id BIGINT NOT NULL,
    task_id BIGINT,
    event_type VARCHAR(64) NOT NULL,
    recipient TEXT NOT NULL,
    carbon_copies TEXT,
    attachments TEXT,
    subject VARCHAR(500),
    template_code VARCHAR(128) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(2000),
    attempted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notification_send_record_outbox (outbox_event_id, attempted_at),
    INDEX idx_notification_send_record_task (task_id, attempted_at)
);

CREATE TABLE task_archive_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE,
    file_snapshot TEXT NOT NULL,
    flow_snapshot TEXT NOT NULL,
    notification_snapshot TEXT NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reviewer_whitelist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_role VARCHAR(64) NOT NULL,
    employee_no VARCHAR(64) NOT NULL,
    display_name VARCHAR(100),
    email VARCHAR(200),
    mobile VARCHAR(32),
    department_name VARCHAR(100),
    created_by_employee_no VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by_employee_no VARCHAR(64),
    deleted_at TIMESTAMP,
    UNIQUE (review_role, employee_no),
    INDEX idx_reviewer_whitelist_role (review_role, employee_no)
);
-- 请勿在此文件维护结构副本，避免历史人员字段和实际部署结构不一致。
-- 初始化数据库时先执行 V1__ddl.sql，再执行 V2__init.sql。
