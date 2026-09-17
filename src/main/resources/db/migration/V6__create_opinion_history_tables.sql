CREATE TABLE opinion_reply (
    id BIGINT PRIMARY KEY,
    opinion_id BIGINT NOT NULL,
    reply_type VARCHAR(32) NOT NULL,
    reason VARCHAR(2000),
    file_version_id BIGINT,
    replied_by BIGINT NOT NULL,
    reply_no INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (opinion_id, reply_no)
);

CREATE TABLE opinion_confirmation (
    id BIGINT PRIMARY KEY,
    opinion_id BIGINT NOT NULL,
    reply_id BIGINT NOT NULL,
    passed BOOLEAN NOT NULL,
    comment VARCHAR(2000),
    confirmed_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_opinion_reply_opinion ON opinion_reply (opinion_id, reply_no);
CREATE INDEX idx_opinion_confirmation_opinion ON opinion_confirmation (opinion_id, created_at);
