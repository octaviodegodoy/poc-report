-- =====================================================================
-- PoC Ingestao - Alinhamento ao contrato TB_JSON_TRANSACAO v2.0
-- 1) eco_product resolvido apenas por Bundle_Name (chave_identificacao_oferta)
-- 2) Lookup de valor bruto (valor_referencia) por id_produto (Subscribed_Plan)
-- =====================================================================

-- ---------------------------------------------------------------------
-- (1) eco_product passa a usar a chave = Bundle_Name (sem concatenar plano)
-- ---------------------------------------------------------------------
DELETE FROM eco_product;

INSERT INTO eco_product (name, chave_identificacao, tipo) VALUES
    ('Vivo Addon Promo 50%',  'Vivo Addon Promo 50%',  'FIXA'),
    ('Vivo Fixa 2S Promo',    'Vivo Fixa 2S Promo',    'FIXA'),
    ('Vivo Fixa 4S',          'Vivo Fixa 4S',          'FIXA'),
    ('Vivo Movel Addon',      'Vivo Movel Addon',      'FIXA'),
    ('Vivo Movel Bundle',     'Vivo Movel Bundle',     'FIXA'),
    ('Vivo Movel Bundle SwA', 'Vivo Movel Bundle SwA', 'FIXA'),
    ('Vivo Total Premium',    'Vivo Total Premium',    'FIXA'),
    ('Vivo Total Standard',   'Vivo Total Standard',   'FIXA'),
    ('Vivo Total SwA',        'Vivo Total SwA',        'FIXA');

-- ---------------------------------------------------------------------
-- (2) Tabela de lookup de valor bruto por produto/plano (Subscribed_Plan)
--     Valores de referencia do contrato v2.0.
-- ---------------------------------------------------------------------
CREATE TABLE eco_valor_referencia (
    id_produto       VARCHAR(120)  NOT NULL PRIMARY KEY,  -- = Subscribed_Plan / genericAttribute3
    valor_referencia NUMERIC(18,4) NOT NULL
);

INSERT INTO eco_valor_referencia (id_produto, valor_referencia) VALUES
    ('Premium',           53.27),
    ('Standard',          39.93),
    ('Standard with ads', 18.59);
