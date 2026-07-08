package br.com.vivo.ingestao.service;

import br.com.vivo.ingestao.api.dto.ImportacaoResult;
import com.opencsv.CSVReader;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ingestao em streaming do CSV de eventos Netflix para tb_json_transacao.
 * Modelo producer-consumer:
 *  - thread leitora: le o CSV e enfileira chunks na BlockingQueue
 *  - N worker threads: consomem chunks, mapeiam e inserem em paralelo
 * Dedup thread-safe via ConcurrentHashMap.newKeySet().
 *
 * Validações implementadas:
 *  - Header: todas as colunas obrigatórias (TO BE / PARC-680) antes de processar qualquer linha
 *  - Por linha: lookup de oferta/produto, formato de data, campos numéricos
 *  - Registros com erro são acumulados em CsvErroReport e disponibilizados para download
 */
@Service
public class NetflixIngestaoService {

    private static final Logger log = LoggerFactory.getLogger(NetflixIngestaoService.class);

    /** Sinal de fim enviado para cada worker ao final da leitura. */
    private static final List<Map<String, String>> SENTINEL = Collections.emptyList();

    /** Tamanho max da fila de chunks (backpressure: leitura para se workers estiverem lentos). */
    private static final int QUEUE_CAPACITY = 64;

    private final String insertSql;
    private final JdbcTemplate jdbcTemplate;
    private final NetflixCsvMapper mapper;
    private final int batchSize;
    private final int workerThreads;

    /**
     * Armazena os CSVs de erro em memória, keyed por UUID gerado por importação.
     * Em produção real isto deveria ser um repositório persistente.
     */
    private final Map<String, byte[]> csvErrosStore = new ConcurrentHashMap<>();

    // Lookups carregados uma vez no startup, imutaveis e compartilhados entre workers
    private Map<String, String>     produtosCache;
    private Map<String, BigDecimal> valoresCache;

    public NetflixIngestaoService(JdbcTemplate jdbcTemplate,
                                  NetflixCsvMapper mapper,
                                  @Value("${app.ingestao.batch-size:5000}")   int batchSize,
                                  @Value("${app.ingestao.worker-threads:4}")  int workerThreads) {
        this.jdbcTemplate  = jdbcTemplate;
        this.mapper        = mapper;
        this.batchSize     = batchSize;
        this.workerThreads = workerThreads;
        this.insertSql     =
                "INSERT INTO tb_json_transacao " +
                "(orderid, linenumber, sublinenumber, periodo, processingunit_name, request) " +
                "VALUES (?, ?, ?, ?, 'ECOPARTNERS', ?::jsonb) " +
                "ON CONFLICT (orderid, linenumber, sublinenumber) DO NOTHING";
    }

    @PostConstruct
    void init() {
        produtosCache = Collections.unmodifiableMap(carregarProdutos());
        valoresCache  = Collections.unmodifiableMap(carregarValoresReferencia());
        log.info("Cache carregado: {} ofertas, {} planos | workers={} batchSize={}",
                produtosCache.size(), valoresCache.size(), workerThreads, batchSize);
    }

    /**
     * Retorna o CSV de erros armazenado para o ID informado, ou null se não encontrado.
     */
    public byte[] obterCsvErros(String csvId) {
        return csvErrosStore.get(csvId);
    }

    public ImportacaoResult importar(MultipartFile arquivo, String periodoReferencia) {
        long inicioTotal = System.nanoTime();

        // Contadores thread-safe
        LongAdder lidas      = new LongAdder();
        LongAdder importadas = new LongAdder();
        LongAdder duplicadas = new LongAdder();
        LongAdder comErro    = new LongAdder();
        LongAdder acumMap    = new LongAdder();
        LongAdder acumIns    = new LongAdder();
        List<String> erros   = Collections.synchronizedList(new ArrayList<>());

        // Relatório de erros por registro (para download posterior)
        CsvErroReport erroReport = new CsvErroReport(NetflixHeaderValidator.COLUNAS_OBRIGATORIAS);

        // Dedup thread-safe, pre-alocado para ~1M entradas
        Set<String> chavesVistas = ConcurrentHashMap.newKeySet(1 << 20);

        BlockingQueue<List<Map<String, String>>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

        // --- Workers ---
        ExecutorService exec = Executors.newFixedThreadPool(workerThreads);
        for (int w = 0; w < workerThreads; w++) {
            exec.submit(() -> {
                while (true) {
                    List<Map<String, String>> chunk;
                    try {
                        chunk = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (chunk == SENTINEL) return; // fim sinalizado

                    List<LinhaTransacao> lote = new ArrayList<>(chunk.size());
                    for (Map<String, String> row : chunk) {
                        lidas.increment();
                        try {
                            long t0 = System.nanoTime();
                            LinhaTransacao linha = mapper.mapear(row, periodoReferencia, produtosCache, valoresCache);
                            acumMap.add(System.nanoTime() - t0);
                            if (!chavesVistas.add(linha.orderid())) {
                                duplicadas.increment();
                                continue;
                            }
                            lote.add(linha);
                        } catch (MapeamentoException e) {
                            comErro.increment();
                            erroReport.adicionar(row, e.getMessage());
                            synchronized (erros) {
                                if (erros.size() < 100) erros.add(e.getMessage());
                            }
                        }
                    }
                    if (!lote.isEmpty()) {
                        long t1 = System.nanoTime();
                        long[] r = inserirLote(lote);
                        acumIns.add(System.nanoTime() - t1);
                        importadas.add(r[0]);
                        duplicadas.add(r[1]);
                    }
                }
            });
        }

        // --- Producer: le CSV, valida header e enfileira chunks ---
        // Usa CSVReader (leitura crua) em vez de CSVReaderHeaderAware.readMap():
        // isso evita que uma linha com numero de campos divergente aborte toda a carga.
        // A contagem de campos e validada por registro; linhas invalidas vao para o CSV de erros.
        long inicioLeitura = System.nanoTime();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(arquivo.getInputStream(), StandardCharsets.UTF_8));
             CSVReader reader = new CSVReader(br)) {

            String[] headerArr = reader.readNext();

            if (headerArr != null) {
                // Normaliza nomes das colunas (trim) e valida o header
                List<String> header = new ArrayList<>(headerArr.length);
                for (String h : headerArr) {
                    header.add(h == null ? "" : h.trim());
                }
                // Valida header: lança HeaderValidationException se faltar coluna obrigatoria
                NetflixHeaderValidator.validar(new java.util.HashSet<>(header));

                final int totalColunas = header.size();
                List<Map<String, String>> chunk = new ArrayList<>(batchSize);

                String[] valores;
                while ((valores = reader.readNext()) != null) {
                    // Validacao de numero de campos por registro:
                    // registro com contagem divergente e invalidado e reportado, mas nao aborta a carga.
                    if (valores.length != totalColunas) {
                        lidas.increment();
                        comErro.increment();
                        Map<String, String> parcial = zipParcial(header, valores);
                        String msg = "Numero de campos invalido: esperado " + totalColunas
                                + ", encontrado " + valores.length;
                        erroReport.adicionar(parcial, msg);
                        synchronized (erros) {
                            if (erros.size() < 100) erros.add(msg);
                        }
                        continue;
                    }

                    Map<String, String> row = new HashMap<>(totalColunas * 2);
                    for (int i = 0; i < totalColunas; i++) {
                        row.put(header.get(i), valores[i]);
                    }

                    chunk.add(row);
                    if (chunk.size() >= batchSize) {
                        queue.put(new ArrayList<>(chunk));
                        chunk.clear();
                    }
                }
                if (!chunk.isEmpty()) queue.put(chunk);
            }

        } catch (HeaderValidationException e) {
            exec.shutdownNow();
            log.warn("Carga abortada - header invalido: {}", e.getMessage());
            throw e; // relançada para o controller retornar 422
        } catch (IOException | com.opencsv.exceptions.CsvValidationException e) {
            exec.shutdownNow();
            log.error("Falha ao ler o arquivo CSV", e);
            throw new IllegalStateException("Falha ao ler o arquivo CSV: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exec.shutdownNow();
            throw new IllegalStateException("Leitura interrompida", e);
        }

        // Sinaliza fim para cada worker (um SENTINEL por worker)
        try {
            for (int i = 0; i < workerThreads; i++) queue.put(SENTINEL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Aguarda workers
        exec.shutdown();
        try {
            exec.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long tempoTotalMs      = (System.nanoTime() - inicioTotal)   / 1_000_000;
        long tempoMapeamentoMs = acumMap.sum()                        / 1_000_000;
        long tempoInsercaoMs   = acumIns.sum()                        / 1_000_000;
        long tempoLeituraMs    = Math.max(0,
                (System.nanoTime() - inicioLeitura) / 1_000_000 - tempoMapeamentoMs - tempoInsercaoMs);

        // Persiste CSV de erros em memória se houver registros defeituosos
        String csvErrosUrl = null;
        if (!erroReport.vazio()) {
            String csvId = UUID.randomUUID().toString();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                erroReport.escrever(baos);
            } catch (IOException e) {
                log.warn("Falha ao serializar CSV de erros", e);
            }
            csvErrosStore.put(csvId, baos.toByteArray());
            csvErrosUrl = "/api/ingestao/erros/" + csvId;
            log.info("CSV de erros gerado: {} registros -> {}", erroReport.tamanho(), csvErrosUrl);
        }

        log.info("Ingestao concluida periodo={} lidas={} importadas={} dup={} erros={} totalMs={} workers={}",
                periodoReferencia, lidas.sum(), importadas.sum(), duplicadas.sum(), comErro.sum(),
                tempoTotalMs, workerThreads);

        return new ImportacaoResult(periodoReferencia,
                lidas.sum(), importadas.sum(), duplicadas.sum(), comErro.sum(),
                Collections.unmodifiableList(erros),
                csvErrosUrl,
                tempoTotalMs, tempoLeituraMs, tempoMapeamentoMs, tempoInsercaoMs);
    }

    /**
     * Monta um mapa parcial coluna->valor para uma linha com contagem de campos divergente.
     * Preenche apenas as posicoes disponiveis (min entre header e valores), preservando
     * o dado bruto para o relatorio de erros.
     */
    private static Map<String, String> zipParcial(List<String> header, String[] valores) {
        int limite = Math.min(header.size(), valores.length);
        Map<String, String> parcial = new HashMap<>(header.size() * 2);
        for (int i = 0; i < limite; i++) {
            parcial.put(header.get(i), valores[i]);
        }
        return parcial;
    }

    /** @return [importadas, duplicadasNoBanco] */
    private long[] inserirLote(List<LinhaTransacao> lote) {
        int[][] resultado = jdbcTemplate.batchUpdate(insertSql, lote, lote.size(),
                (ps, linha) -> {
                    ps.setString(1, linha.orderid());
                    ps.setInt(2, linha.lineNumber());
                    ps.setInt(3, linha.subLineNumber());
                    ps.setString(4, linha.periodo());
                    ps.setString(5, linha.requestJson());
                });
        long importadas = 0;
        long duplicadas = 0;
        for (int[] grupo : resultado) {
            for (int r : grupo) {
                if (r == 0) {
                    duplicadas++;
                } else {
                    importadas++;
                }
            }
        }
        return new long[]{importadas, duplicadas};
    }

    private Map<String, String> carregarProdutos() {
        Map<String, String> mapa = new HashMap<>();
        jdbcTemplate.query("SELECT chave_identificacao, name FROM eco_product",
                rs -> {
                    mapa.put(rs.getString("chave_identificacao"), rs.getString("name"));
                });
        return mapa;
    }

    private Map<String, BigDecimal> carregarValoresReferencia() {
        Map<String, BigDecimal> mapa = new HashMap<>();
        jdbcTemplate.query("SELECT id_produto, valor_referencia FROM eco_valor_referencia",
                rs -> {
                    mapa.put(rs.getString("id_produto"), rs.getBigDecimal("valor_referencia"));
                });
        return mapa;
    }
}
