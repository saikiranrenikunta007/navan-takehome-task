-- Receipt → Transaction identity, taxes, and auto-itemize line items.
-- Money is stored as DECIMAL(19,2); currency is ISO-4217 (3 chars).

CREATE TABLE receipts (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    original_name   VARCHAR(512) NOT NULL,
    content_type    VARCHAR(255),
    content_sha256  VARCHAR(64)  NOT NULL,
    storage_key     VARCHAR(1024) NOT NULL,
    raw_ocr_text    LONGTEXT,
    processed       BOOLEAN      NOT NULL DEFAULT FALSE,
    uploaded_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- Identical file bytes must not create a second processable receipt.
    CONSTRAINT uq_receipts_sha256 UNIQUE (content_sha256)
);

CREATE TABLE transactions (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    receipt_id      BIGINT        NOT NULL,
    merchant        VARCHAR(512)  NOT NULL,
    txn_date        DATE          NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    grand_total     DECIMAL(19,2) NOT NULL,
    itemize_status  VARCHAR(32)   NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- One receipt → one transaction. Enforced by the database, not app code.
    CONSTRAINT uq_transactions_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_transactions_receipt FOREIGN KEY (receipt_id)
        REFERENCES receipts (id)
);

CREATE TABLE taxes (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    transaction_id  BIGINT        NOT NULL,
    name            VARCHAR(128)  NOT NULL,
    rate            DECIMAL(9,4),
    amount          DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_taxes_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE
);

CREATE TABLE line_items (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    transaction_id  BIGINT        NOT NULL,
    description     VARCHAR(512)  NOT NULL,
    amount          DECIMAL(19,2) NOT NULL,
    quantity        INT,
    tax_amount      DECIMAL(19,2),
    PRIMARY KEY (id),
    CONSTRAINT fk_line_items_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE
);
