-- PCB / 原理图评审平台最终 DDL（仅用于全新数据库）

CREATE TABLE review_task (
    id BIGINT PRIMARY KEY,
    review_type VARCHAR(32) NOT NULL,
    task_name VARCHAR(200) NOT NULL,
    project_name VARCHAR(200) NOT NULL,
    designer_id BIGINT NOT NULL,
    design_name VARCHAR(200) NOT NULL,
    designer_name VARCHAR(100) NOT NULL DEFAULT '',
    pcb_type VARCHAR(64),
    status VARCHAR(64) NOT NULL,
    initial_file_ids VARCHAR(1000) NOT NULL,
    expected_completed_date DATE,
    expert_leader_id BIGINT,
    expert_leader_name VARCHAR(100),
    review_roles VARCHAR(500) NOT NULL DEFAULT '',
    reviewer_assignments VARCHAR(4000) NOT NULL DEFAULT '',
    assigned_reviewer_ids VARCHAR(2000) NOT NULL DEFAULT '',
    review_description VARCHAR(2000),
    version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_review_task_query ON review_task (review_type, status, designer_id);
CREATE INDEX idx_review_task_designer_name ON review_task (designer_name);

CREATE TABLE review_opinion (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_item_id BIGINT,
    severity VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    rich_text_content LONGTEXT,
    raised_by BIGINT NOT NULL,
    raised_by_name VARCHAR(100) NOT NULL,
    status VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_review_opinion_task_status ON review_opinion (task_id, status, raised_by);

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
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_review_file_latest ON review_file (task_id, file_category, is_latest);
CREATE INDEX idx_review_file_owner ON review_file (uploaded_by, uploaded_at);

CREATE TABLE opinion_reply (
    id BIGINT PRIMARY KEY,
    opinion_id BIGINT NOT NULL,
    reply_type VARCHAR(32) NOT NULL,
    reason VARCHAR(2000),
    replied_by BIGINT NOT NULL,
    reply_no INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (opinion_id, reply_no)
);
CREATE INDEX idx_opinion_reply_opinion ON opinion_reply (opinion_id, reply_no);

CREATE TABLE opinion_confirmation (
    id BIGINT PRIMARY KEY,
    opinion_id BIGINT NOT NULL,
    reply_id BIGINT NOT NULL,
    passed BOOLEAN NOT NULL,
    comment VARCHAR(2000),
    confirmed_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_opinion_confirmation_opinion ON opinion_confirmation (opinion_id, created_at);

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
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_check_template_type_enabled ON check_item_template (review_type, enabled, sort_no);
CREATE INDEX idx_check_template_parent ON check_item_template (parent_id, sort_no);

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
    UNIQUE (task_id, item_id)
);
CREATE INDEX idx_task_check_item_task ON task_check_item (task_id, sort_no);
CREATE INDEX idx_task_check_item_parent ON task_check_item (task_id, parent_id, sort_no);

CREATE TABLE task_flow_record (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    action_name VARCHAR(100) NOT NULL,
    operate_id BIGINT NOT NULL,
    comment VARCHAR(2000),
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_task_flow_record_task ON task_flow_record (task_id, create_at);

CREATE TABLE outbox_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL
);
CREATE INDEX idx_outbox_event_status_created ON outbox_event (status, created_at);

CREATE TABLE notification_send_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    outbox_event_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    recipient VARCHAR(320) NOT NULL,
    template_code VARCHAR(128) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(2000),
    attempted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notification_send_record_outbox ON notification_send_record (outbox_event_id, attempted_at);

CREATE TABLE task_archive_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE,
    file_snapshot TEXT NOT NULL,
    notification_snapshot TEXT NOT NULL,
    archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_account (
    id BIGINT PRIMARY KEY,
    employee_no VARCHAR(64),
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(200) NOT NULL,
    mobile VARCHAR(32),
    department_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (employee_no),
    UNIQUE (email)
);
CREATE INDEX idx_user_account_department ON user_account (department_name, enabled);

CREATE TABLE role_definition (
    role_code VARCHAR(64) PRIMARY KEY,
    role_name VARCHAR(100) NOT NULL,
    role_description VARCHAR(500) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_code),
    CONSTRAINT fk_user_role_account FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_user_role_definition FOREIGN KEY (role_code) REFERENCES role_definition (role_code)
);
CREATE INDEX idx_user_role_role_code ON user_role (role_code, user_id);

CREATE TABLE reviewer_whitelist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_role VARCHAR(64) NOT NULL,
    employee_no VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by BIGINT,
    deleted_at TIMESTAMP,
    UNIQUE (review_role, employee_no)
);
CREATE INDEX idx_reviewer_whitelist_role ON reviewer_whitelist (review_role, employee_no);
