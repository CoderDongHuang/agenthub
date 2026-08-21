CREATE TABLE channel_binding (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL,
    external_account_id VARCHAR(255) NOT NULL,
    agent_id BIGINT NOT NULL REFERENCES agent_definition(id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (channel, external_account_id)
);

CREATE INDEX idx_channel_binding_tenant ON channel_binding(tenant_id, channel, enabled);

ALTER TABLE channel_delivery DROP CONSTRAINT IF EXISTS channel_delivery_channel_external_message_id_direction_key;
ALTER TABLE channel_delivery ADD CONSTRAINT uq_channel_delivery_tenant_message
    UNIQUE (tenant_id, channel, external_message_id, direction);

ALTER TABLE channel_delivery ADD COLUMN worker_id VARCHAR(100);
ALTER TABLE channel_delivery ADD COLUMN lease_expires_at TIMESTAMP;
ALTER TABLE channel_delivery DROP CONSTRAINT IF EXISTS channel_delivery_status_check;
ALTER TABLE channel_delivery ADD CONSTRAINT channel_delivery_status_check
    CHECK (status IN ('accepted', 'processing', 'delivered', 'retrying', 'dead_letter'));
CREATE INDEX idx_channel_inbound_queue ON channel_delivery(status, created_at)
    WHERE direction = 'inbound' AND status IN ('accepted', 'retrying');
