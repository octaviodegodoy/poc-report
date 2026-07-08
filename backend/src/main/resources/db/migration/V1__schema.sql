-- =====================================================================
-- PoC Ingestao - TB_JSON_TRANSACAO + tabela de apoio eco_product
-- Banco: PostgreSQL (provisorio em Docker)
-- =====================================================================

-- Tabela de transacoes canonicas (envelope REQUEST em JSON)
CREATE TABLE tb_json_transacao (
    id              BIGSERIAL PRIMARY KEY,
    orderid         VARCHAR(120) NOT NULL,           -- hash SHA-256 do evento
    linenumber      INTEGER      NOT NULL DEFAULT 1,
    sublinenumber   INTEGER      NOT NULL DEFAULT 1,
    valor_sap       NUMERIC(18,4),
    regra           VARCHAR(255),
    periodo         VARCHAR(7)   NOT NULL,           -- YYYY-MM
    processingunit_name VARCHAR(120) NOT NULL DEFAULT 'ECOPARTNERS',
    request         JSONB        NOT NULL,
    criado_em       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_transacao_chave UNIQUE (orderid, linenumber, sublinenumber),
    CONSTRAINT ck_linenumber CHECK (linenumber > 0),
    CONSTRAINT ck_sublinenumber CHECK (sublinenumber > 0),
    CONSTRAINT ck_periodo CHECK (periodo ~ '^[0-9]{4}-[0-9]{2}$')
);

CREATE INDEX ix_transacao_periodo_pu ON tb_json_transacao (periodo, processingunit_name);
CREATE INDEX ix_transacao_chave      ON tb_json_transacao (orderid, linenumber, sublinenumber);

-- Tabela de apoio: resolucao da oferta (Subscribed Plan|Bundle Name) -> produto
CREATE TABLE eco_product (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(255) NOT NULL,      -- com separador pipe "|"
    chave_identificacao  VARCHAR(255) NOT NULL UNIQUE,
    tipo                 VARCHAR(40)  NOT NULL DEFAULT 'FIXA'
);

-- Seed com a chave em formato PIPE (Subscribed Plan|Bundle Name), conforme contrato
INSERT INTO eco_product (name, chave_identificacao, tipo) VALUES
    ('Standard with ads|Vivo Movel Addon',        'Standard with ads|Vivo Movel Addon',        'FIXA'),
    ('Premium|Vivo Movel Addon',                  'Premium|Vivo Movel Addon',                  'FIXA'),
    ('Standard with ads|Vivo Movel Bundle SwA',   'Standard with ads|Vivo Movel Bundle SwA',   'FIXA'),
    ('Standard|Vivo Movel Addon',                 'Standard|Vivo Movel Addon',                 'FIXA'),
    ('Premium|Vivo Movel Bundle',                 'Premium|Vivo Movel Bundle',                 'FIXA');
