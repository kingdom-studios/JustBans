-- JustBans initial schema. Requires MariaDB 10.7+ (native UUID type).

CREATE TABLE jb_players (
    uuid            UUID          NOT NULL PRIMARY KEY,
    last_name       VARCHAR(16)   NOT NULL,
    last_name_lower VARCHAR(16)   NOT NULL,
    first_seen      TIMESTAMP(3)  NOT NULL,
    last_seen       TIMESTAMP(3)  NOT NULL,
    last_server     VARCHAR(64)   NULL,
    last_ip         VARBINARY(16) NULL,
    INDEX idx_players_name (last_name_lower, last_seen)
);

CREATE TABLE jb_player_names (
    uuid       UUID         NOT NULL,
    name       VARCHAR(16)  NOT NULL,
    name_lower VARCHAR(16)  NOT NULL,
    first_seen TIMESTAMP(3) NOT NULL,
    last_seen  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (uuid, name_lower),
    INDEX idx_names_lower (name_lower),
    CONSTRAINT fk_names_player FOREIGN KEY (uuid) REFERENCES jb_players (uuid) ON DELETE CASCADE
);

CREATE TABLE jb_servers (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    group_name     VARCHAR(64)  NULL,
    display_name   VARCHAR(64)  NULL,
    last_heartbeat TIMESTAMP(3) NULL
);

CREATE TABLE jb_punishments (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id        CHAR(8)      NOT NULL,
    type             ENUM('BAN','WARN','KICK') NOT NULL,
    scope_type       ENUM('NETWORK','SERVER','GROUP') NOT NULL DEFAULT 'NETWORK',
    scope_target     VARCHAR(64)  NOT NULL DEFAULT '*',

    target_uuid      UUID         NOT NULL,
    target_name      VARCHAR(16)  NOT NULL,

    actor_type       ENUM('PLAYER','CONSOLE','WEB','SYSTEM') NOT NULL,
    actor_uuid       UUID         NULL,
    actor_ref        VARCHAR(64)  NULL,
    actor_name       VARCHAR(64)  NOT NULL,

    reason_id        VARCHAR(32)  NULL,
    reason_text      VARCHAR(512) NOT NULL,
    note             VARCHAR(512) NULL,

    created_at       TIMESTAMP(3) NOT NULL,
    expires_at       TIMESTAMP(3) NULL,
    origin_server    VARCHAR(64)  NULL,
    silent           BOOLEAN      NOT NULL DEFAULT FALSE,
    ladder_step      SMALLINT     NULL,

    revoked_at       TIMESTAMP(3) NULL,
    revoked_by_type  ENUM('PLAYER','CONSOLE','WEB','SYSTEM') NULL,
    revoked_by_uuid  UUID         NULL,
    revoked_by_name  VARCHAR(64)  NULL,
    revoke_reason    VARCHAR(512) NULL,

    request_id       UUID         NULL,

    UNIQUE KEY uq_public_id (public_id),
    UNIQUE KEY uq_request_id (request_id),
    INDEX idx_target_active (target_uuid, type, revoked_at, expires_at),
    INDEX idx_scope (scope_type, scope_target),
    INDEX idx_created (created_at),
    INDEX idx_actor (actor_uuid)
);

CREATE TABLE jb_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_type    VARCHAR(40)  NOT NULL,
    punishment_id BIGINT       NULL,
    payload       JSON         NOT NULL,
    origin_node   VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL,
    published_at  TIMESTAMP(3) NULL,
    INDEX idx_unpublished (published_at, id),
    INDEX idx_punishment (punishment_id),
    CONSTRAINT fk_events_punishment FOREIGN KEY (punishment_id) REFERENCES jb_punishments (id) ON DELETE SET NULL
);

CREATE TABLE jb_reasons (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    display_name  VARCHAR(128) NOT NULL,
    allowed_types VARCHAR(128) NOT NULL,
    definition    JSON         NOT NULL,
    sort_order    INT          NOT NULL DEFAULT 0,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMP(3) NOT NULL
);

CREATE TABLE jb_command_log (
    request_id  UUID         NOT NULL PRIMARY KEY,
    action      VARCHAR(40)  NOT NULL,
    received_at TIMESTAMP(3) NOT NULL,
    handled_by  VARCHAR(64)  NOT NULL,
    status      ENUM('PROCESSING','OK','ERROR') NOT NULL,
    result      JSON         NULL,
    INDEX idx_received (received_at)
);
