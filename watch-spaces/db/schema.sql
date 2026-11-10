-- MySQL 8 schema, equivalent to what Hibernate generates (spring.jpa.hibernate.ddl-auto=update).
-- Use this file if you prefer to create the schema by hand and run the app with DDL_AUTO=validate.
CREATE DATABASE IF NOT EXISTS watchspaces CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE watchspaces;

CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(190) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  role VARCHAR(16) NOT NULL,                 -- VIEWER | HOST | ADMIN
  subtitle_locale VARCHAR(16) NOT NULL DEFAULT 'en',
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE refresh_tokens (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL,           -- SHA-256 of the opaque token
  expires_at DATETIME(6) NOT NULL,
  revoked BIT NOT NULL DEFAULT 0,
  UNIQUE KEY idx_refresh_hash (token_hash),
  CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE titles (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  duration_seconds INT NOT NULL,
  genres VARCHAR(200) NOT NULL,
  video_asset_url VARCHAR(500),
  created_at DATETIME(6) NOT NULL
);

CREATE TABLE timeline_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title_id BIGINT NOT NULL,
  ts_seconds INT NOT NULL,
  end_ts_seconds INT,
  event_type VARCHAR(20) NOT NULL,           -- SCENE | CHARACTER | TRIVIA | GLOSSARY | VARIATION_POINT
  name VARCHAR(200),
  body VARCHAR(2000),
  keywords VARCHAR(500),
  variation_id VARCHAR(64),
  variation_kind VARCHAR(16),                -- SUBTITLE | NARRATIVE
  KEY idx_timeline_title_ts (title_id, ts_seconds),
  CONSTRAINT fk_te_title FOREIGN KEY (title_id) REFERENCES titles(id)
);

CREATE TABLE variation_options (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  option_key VARCHAR(32) NOT NULL,
  label VARCHAR(300) NOT NULL,
  locale VARCHAR(16),
  asset_ref VARCHAR(300),
  sort_order INT NOT NULL,                   -- 0 = original option
  CONSTRAINT uk_option_event_key UNIQUE (event_id, option_key),
  CONSTRAINT fk_vo_event FOREIGN KEY (event_id) REFERENCES timeline_events(id)
);

CREATE TABLE watch_spaces (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  invite_code VARCHAR(16) NOT NULL,
  name VARCHAR(120) NOT NULL,
  title_id BIGINT NOT NULL,
  host_user_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,               -- SCHEDULED | LIVE | ENDED
  privacy VARCHAR(16) NOT NULL,              -- PUBLIC_LINK | INVITE_ONLY
  max_participants INT NOT NULL,
  ai_verbosity VARCHAR(16) NOT NULL,         -- MINIMAL | NORMAL | CHATTY
  voting_enabled BIT NOT NULL,
  trivia_enabled BIT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  started_at DATETIME(6),
  ended_at DATETIME(6),
  snap_peak_participants INT, snap_ai_questions INT, snap_trivia_cards INT,
  snap_chat_messages INT, snap_duration_seconds BIGINT,
  CONSTRAINT uk_ws_invite UNIQUE (invite_code),
  KEY idx_ws_host (host_user_id), KEY idx_ws_status (status),
  CONSTRAINT fk_ws_title FOREIGN KEY (title_id) REFERENCES titles(id),
  CONSTRAINT fk_ws_host FOREIGN KEY (host_user_id) REFERENCES users(id)
);

CREATE TABLE watch_space_participants (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  watch_space_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  joined_at DATETIME(6) NOT NULL,
  left_at DATETIME(6),
  CONSTRAINT uk_wsp_ws_user UNIQUE (watch_space_id, user_id),
  CONSTRAINT fk_wsp_ws FOREIGN KEY (watch_space_id) REFERENCES watch_spaces(id),
  CONSTRAINT fk_wsp_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE chat_messages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_uuid VARCHAR(36) NOT NULL,
  watch_space_id BIGINT NOT NULL,
  user_id BIGINT,
  msg_type VARCHAR(16) NOT NULL,             -- CHAT | AI_ANSWER | SYSTEM
  body VARCHAR(4000) NOT NULL,
  seq BIGINT NOT NULL,
  ts_seconds DOUBLE NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_chat_uuid UNIQUE (message_uuid),
  KEY idx_chat_ws_seq (watch_space_id, seq),
  CONSTRAINT fk_cm_ws FOREIGN KEY (watch_space_id) REFERENCES watch_spaces(id),
  CONSTRAINT fk_cm_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE interactions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title_id BIGINT NOT NULL,
  watched_seconds INT NOT NULL,
  completed BIT NOT NULL,
  rating INT,
  co_watched_with VARCHAR(1000),
  created_at DATETIME(6) NOT NULL,
  KEY idx_inter_user (user_id), KEY idx_inter_title (title_id),
  CONSTRAINT fk_in_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_in_title FOREIGN KEY (title_id) REFERENCES titles(id)
);

CREATE TABLE room_variations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  watch_space_id BIGINT NOT NULL,
  event_id BIGINT NOT NULL,
  status VARCHAR(8) NOT NULL,
  opened_at DATETIME(6) NOT NULL,
  closes_at DATETIME(6) NOT NULL,
  winning_option_key VARCHAR(32),
  CONSTRAINT uk_rv_ws_event UNIQUE (watch_space_id, event_id),
  CONSTRAINT fk_rv_ws FOREIGN KEY (watch_space_id) REFERENCES watch_spaces(id),
  CONSTRAINT fk_rv_event FOREIGN KEY (event_id) REFERENCES timeline_events(id)
);

CREATE TABLE variation_votes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  room_variation_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  option_key VARCHAR(32) NOT NULL,
  CONSTRAINT uk_vote_rv_user UNIQUE (room_variation_id, user_id),
  CONSTRAINT fk_vv_rv FOREIGN KEY (room_variation_id) REFERENCES room_variations(id),
  CONSTRAINT fk_vv_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE event_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  watch_space_id BIGINT NOT NULL,
  user_id BIGINT,
  event_type VARCHAR(16) NOT NULL,
  detail VARCHAR(300),
  created_at DATETIME(6) NOT NULL,
  KEY idx_evlog_ws_type (watch_space_id, event_type),
  CONSTRAINT fk_el_ws FOREIGN KEY (watch_space_id) REFERENCES watch_spaces(id)
);
