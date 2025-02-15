CREATE TABLE mail_account (
  key BIGINT,
  type TEXT NOT NULL,
  name TEXT NOT NULL,
  email TEXT NOT NULL UNIQUE,
  timestamp BIGINT NOT NULL,
  PRIMARY KEY (key)
);

CREATE TABLE mail_data (
  id TEXT,
  raw_body TEXT NOT NULL,
  account_id BIGINT NOT NULL,
  internal_date BIGINT NOT NULL,
  timestamp BIGINT NOT NULL,
  PRIMARY KEY (id)
);

-- Indexes
CREATE INDEX idx_mail_account_email ON mail_account(email);

CREATE INDEX idx_mail_data_account ON mail_data(account_id);
CREATE INDEX idx_mail_data_internal_date ON mail_data(internal_date);

-- Event log

CREATE TABLE IF NOT EXISTS mail_log (
  version BIGINT NOT NULL PRIMARY KEY,
  aggregate_id BIGINT NOT NULL,
  discriminator TEXT NOT NULL,
  namespace INT NOT NULL,
  reference BIGINT,
  parent_id BIGINT,
  grand_parent_id BIGINT,
  props JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload BYTEA NOT NULL
);

CREATE INDEX idx_mail_log_discriminator ON mail_log (discriminator);
CREATE INDEX idx_mail_log_aggregate_id ON mail_log (aggregate_id);
CREATE INDEX idx_mail_log_namespace ON mail_log (namespace);
CREATE INDEX idx_mail_log_reference ON mail_log (reference);
CREATE INDEX idx_mail_log_parent_id ON mail_log (parent_id);
CREATE INDEX idx_mail_log_grand_parent_id ON mail_log (grand_parent_id);

CREATE INDEX idx_mail_log_props_all_keys ON mail_log USING GIN (props);

-- Download log

CREATE TABLE IF NOT EXISTS download_log (
  version BIGINT NOT NULL PRIMARY KEY,
  aggregate_id BIGINT NOT NULL,
  discriminator TEXT NOT NULL,
  namespace INT NOT NULL,
  reference BIGINT,
  parent_id BIGINT,
  grand_parent_id BIGINT,
  props JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload BYTEA NOT NULL
);

CREATE INDEX idx_download_log_discriminator ON download_log (discriminator);
CREATE INDEX idx_download_log_aggregate_id ON download_log (aggregate_id);
CREATE INDEX idx_download_log_namespace ON download_log (namespace);
CREATE INDEX idx_download_log_reference ON download_log (reference);
CREATE INDEX idx_download_log_parent_id ON download_log (parent_id);
CREATE INDEX idx_download_log_grand_parent_id ON download_log (grand_parent_id);

CREATE INDEX idx_download_log_props_all_keys ON download_log USING GIN (props);

-- Authorization log

CREATE TABLE IF NOT EXISTS authorization_log (
  version BIGINT NOT NULL PRIMARY KEY,
  aggregate_id BIGINT NOT NULL,
  discriminator TEXT NOT NULL,
  namespace INT NOT NULL,
  reference BIGINT,
  parent_id BIGINT,
  grand_parent_id BIGINT,
  props JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload BYTEA NOT NULL
);

CREATE INDEX idx_authorization_log_discriminator ON authorization_log (discriminator);
CREATE INDEX idx_authorization_log_aggregate_id ON authorization_log (aggregate_id);
CREATE INDEX idx_authorization_log_namespace ON authorization_log (namespace);
CREATE INDEX idx_authorization_log_reference ON authorization_log (reference);
CREATE INDEX idx_authorization_log_parent_id ON authorization_log (parent_id);
CREATE INDEX idx_authorization_log_grand_parent_id ON authorization_log (grand_parent_id);

CREATE INDEX idx_authorization_log_props_all_keys ON authorization_log USING GIN (props);

-- Config log

CREATE TABLE IF NOT EXISTS mail_config_log (
  version BIGINT NOT NULL PRIMARY KEY,
  aggregate_id BIGINT NOT NULL,
  discriminator TEXT NOT NULL,
  namespace INT NOT NULL,
  reference BIGINT,
  parent_id BIGINT,
  grand_parent_id BIGINT,
  props JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload BYTEA NOT NULL
);

CREATE INDEX idx_mail_config_log_discriminator ON mail_config_log (discriminator);
CREATE INDEX idx_mail_config_log_aggregate_id ON mail_config_log (aggregate_id);
CREATE INDEX idx_mail_config_log_namespace ON mail_config_log (namespace);
CREATE INDEX idx_mail_config_log_reference ON mail_config_log (reference);
CREATE INDEX idx_mail_config_log_parent_id ON mail_config_log (parent_id);
CREATE INDEX idx_mail_config_log_grand_parent_id ON mail_config_log (grand_parent_id);

CREATE INDEX idx_mail_config_log_props_all_keys ON mail_config_log USING GIN (props);


