-- Customer-facing order updates, written by the Kafka consumer of order events.
-- Kafka delivers at least once, so the same event can arrive twice; the unique key makes the
-- consumer idempotent (a repeat insert is ignored).
CREATE TABLE notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    order_id   BIGINT       NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    message    VARCHAR(255) NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_order_status (order_id, status),
    KEY idx_notifications_user_created (user_id, created_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
