# PoC Ingestão · TB_JSON_TRANSACAO

Projeto enxuto para **ingestão de eventos de parceiros** (Netflix Bundle Invoice) e
geração das transações canônicas em `tb_json_transacao`, com banco **PostgreSQL provisório em Docker**.

## Estrutura

```
poc-ingestao-transacao/
├── docker-compose.yml          # Postgres 16 (provisório)
├── poc-ingestao-transacao.code-workspace
├── backend/                    # Spring Boot 3.3.2 · Java 17 · Flyway · OpenCSV
└── frontend/                   # React 18 · Vite 5 · Mística (só a tela de ingestão)
```

## Pré-requisitos

- Docker Desktop
- JDK 17 + Maven
- Node.js 18+

## Como executar

### Opção rápida (Windows)

```bat
start.bat
```

O script sobe o PostgreSQL via Docker Compose e inicia backend + frontend.

Para encerrar:

```bat
stop.bat
```

### 1. Banco (Postgres provisório)

```powershell
docker compose up -d
```

Sobe Postgres em `localhost:5432` (db `ingestao`, usuário/senha `ingestao`).

### 2. Backend

```powershell
cd backend
mvn spring-boot:run
```

- API em `http://localhost:8080`.
- O **Flyway** cria `tb_json_transacao` e a tabela de apoio `eco_product`
  (com as ofertas no formato **pipe** `Subscribed Plan|Bundle Name`).

### 3. Frontend

```powershell
cd frontend
npm install
npm run dev
```

- Abre em `http://localhost:5173` (proxy `/api` → `:8080`).

## Tela de Ingestão

Campos:

- **Período de referência** (`periodo_referencia`, formato `AAAA-MM`) — define
  `calendar`/`periodoCompensacao` das transações da carga.
- **Arquivo de eventos (CSV)**.

Resultado: linhas lidas, importadas, duplicadas ignoradas e com erro.

## Regras de mapeamento aplicadas

| Destino (REQUEST)                          | Origem (CSV)                                                  |
| ------------------------------------------ | ------------------------------------------------------------ |
| `salesTransaction.salesOrder.id` / ORDERID | SHA-256 determinístico de **todos os campos da linha** do arquivo do parceiro |
| `product.id` / `product.name`              | `eco_product.name` resolvido por `chave_identificacao_oferta` = `Bundle Name` |
| `value.value`                              | `eco_valor_referencia.valor_referencia` (lookup por `Subscribed Plan`) |
| `genericNumber1`                           | `Base Plan Discount Rate`                                     |
| `genericNumber2`                           | `Billing Cycle Days`                                          |
| `genericNumber3`                           | `Subscrn Billing Cycle Days`                                  |
| `compensationDate`                         | `Event Date` (M/D/YYYY → YYYY-MM-DD)                          |
| `genericAttribute1`                        | `Member Cohort`                                              |
| `genericAttribute2`                        | `Payment Program Type`                                       |
| `genericAttribute3`                        | `Subscribed Plan` (id_produto; direciona a lookup de valor)  |
| `calendar` / `periodoCompensacao`          | **periodo_referencia** (da tela)                             |

Constantes: `eventType=COBRANDED_NETFLIX`, `product.type=FIXA`,
`businessUnit=RESIDENCIAL`, `processingunit_name=ECOPARTNERS`.

## Performance & deduplicação

- Leitura **em streaming** (OpenCSV `readMap`), sem carregar o arquivo inteiro.
- Inserção **em lotes** (`batch-size`, default 1000) via `JdbcTemplate.batchUpdate`.
- **Dedup**: unicidade do **hash da linha** (ORDERID) — `HashSet` por arquivo +
  `UNIQUE(orderid, linenumber, sublinenumber)` com `INSERT ... ON CONFLICT DO NOTHING` no Postgres.

## Endpoint

`POST /api/ingestao/importar` (multipart): `file` (CSV) + `periodo_referencia` (`AAAA-MM`).
