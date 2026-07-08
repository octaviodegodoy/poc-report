package br.com.vivo.ingestao.api;

import br.com.vivo.ingestao.api.dto.ImportacaoResult;
import br.com.vivo.ingestao.service.HeaderValidationException;
import br.com.vivo.ingestao.service.NetflixIngestaoService;
import br.com.vivo.ingestao.service.converter.JsonToCsvConverter;
import br.com.vivo.ingestao.service.converter.JsonToExcelConverter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/ingestao")
public class IngestaoController {

    private static final Pattern PERIODO = Pattern.compile("^[0-9]{4}-[0-9]{2}$");

    private final NetflixIngestaoService service;
    private final JsonToExcelConverter jsonToExcelConverter;
    private final JsonToCsvConverter jsonToCsvConverter;

    public IngestaoController(
            NetflixIngestaoService service,
            JsonToExcelConverter jsonToExcelConverter,
            JsonToCsvConverter jsonToCsvConverter) {
        this.service = service;
        this.jsonToExcelConverter = jsonToExcelConverter;
        this.jsonToCsvConverter = jsonToCsvConverter;
    }

    @PostMapping("/importar")
    @CrossOrigin(origins = "${app.cors.allowed-origins}")
    public ResponseEntity<?> importar(
            @RequestParam("file") MultipartFile file,
            @RequestParam("periodo_referencia") String periodoReferencia) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "Arquivo CSV obrigatorio"));
        }
        if (periodoReferencia == null || !PERIODO.matcher(periodoReferencia).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("mensagem", "periodo_referencia deve estar no formato YYYY-MM"));
        }

        try {
            ImportacaoResult resultado = service.importar(file, periodoReferencia);
            return ResponseEntity.ok(resultado);
        } catch (HeaderValidationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("mensagem", e.getMessage(), "status", "CARGA_MAL_SUCEDIDA"));
        }
    }

    /**
     * Download do CSV de registros com erro gerado durante a última importação.
     * A URL é retornada no campo {@code csvErrosUrl} do resultado de {@code /importar}.
     */
    @GetMapping("/erros/{csvId}")
    @CrossOrigin(origins = "${app.cors.allowed-origins}")
    public ResponseEntity<byte[]> downloadCsvErros(@PathVariable String csvId) {
        byte[] conteudo = service.obterCsvErros(csvId);
        if (conteudo == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename("erros_" + csvId + ".csv").build());

        return ResponseEntity.ok().headers(headers).body(conteudo);
    }

    /**
     * Converte um arquivo JSON já salvo em {@code backend/src/main/resources}
     * (ver {@link #salvarJsonEmResources}) em uma planilha Excel e devolve o
     * binário diretamente na resposta. Não recebe upload — só o nome do
     * arquivo já presente em resources, evitando transferir o mesmo arquivo
     * grande duas vezes pela rede (uma para salvar, outra para converter).
     */
    @GetMapping("/converter-excel")
    @CrossOrigin(origins = "${app.cors.allowed-origins}")
    public ResponseEntity<?> converterJsonParaExcel(
            @RequestParam("arquivo") String nomeArquivoParam,
            HttpServletResponse response) throws IOException {

        return converterArquivoResources(
                nomeArquivoParam, response,
                "json-excel-out-", ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                jsonToExcelConverter::convertJsonToExcel,
                "Falha ao converter JSON para Excel: ");
    }

    /**
     * Mesma ideia de {@link #converterJsonParaExcel}, mas gera CSV via
     * {@link JsonToCsvConverter} em vez de planilha Excel.
     */
    @GetMapping("/converter-csv")
    @CrossOrigin(origins = "${app.cors.allowed-origins}")
    public ResponseEntity<?> converterJsonParaCsv(
            @RequestParam("arquivo") String nomeArquivoParam,
            HttpServletResponse response) throws IOException {

        return converterArquivoResources(
                nomeArquivoParam, response,
                "json-csv-out-", ".csv",
                "text/csv; charset=UTF-8",
                jsonToCsvConverter::convertJsonToCsv,
                "Falha ao converter JSON para CSV: ");
    }

    /**
     * Lógica comum de {@link #converterJsonParaExcel} e {@link #converterJsonParaCsv}:
     * resolve {@code nomeArquivoParam} dentro de {@code backend/src/main/resources},
     * roda {@code conversor} (referência de método para
     * {@code JsonToExcelConverter#convertJsonToExcel} ou
     * {@code JsonToCsvConverter#convertJsonToCsv}) para um arquivo temporário,
     * e devolve o resultado.
     * <p>
     * Escreve direto no {@link HttpServletResponse} em vez de retornar o corpo
     * via {@code ResponseEntity}/conversores do Spring. Duas razões: (1) o
     * arquivo gerado (centenas de MB para JSONs de entrada na casa dos GB) é
     * copiado do disco pro socket sem nunca ser carregado inteiro em memória;
     * (2) evita depender de {@code StreamingResponseBody}, cuja detecção pelo
     * Spring exige o tipo genérico exato no retorno do método (não funciona
     * com o {@code ResponseEntity<?>} usado nos outros endpoints) e passa a
     * requisição pelo processamento assíncrono do Spring — com timeout
     * próprio, independente do {@code spring.mvc.async.request-timeout}.
     * Escrever direto no response evita as duas armadilhas.
     * <p>
     * Retorna {@code null} nas respostas de sucesso: é o sinal padrão do Spring
     * MVC de "a resposta já foi totalmente escrita pelo método" quando o
     * retorno declarado é {@code ResponseEntity<?>}.
     */
    private ResponseEntity<?> converterArquivoResources(
            String nomeArquivoParam,
            HttpServletResponse response,
            String prefixoTemp,
            String extensaoSaida,
            String contentType,
            ConversorArquivo conversor,
            String mensagemErroPrefixo) throws IOException {

        if (nomeArquivoParam == null || nomeArquivoParam.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "Parametro 'arquivo' obrigatorio"));
        }

        String nomeArquivo = Path.of(nomeArquivoParam).getFileName().toString();
        if (!nomeArquivo.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "Apenas arquivos .json sao aceitos"));
        }

        Path entrada;
        try {
            entrada = resolverArquivoEmResources(nomeArquivo, false);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        }

        if (!Files.isRegularFile(entrada)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("mensagem", "Arquivo nao encontrado em resources: " + nomeArquivo));
        }

        Path tempSaida;
        try {
            tempSaida = Files.createTempFile(prefixoTemp, extensaoSaida);
            conversor.converter(entrada.toString(), tempSaida.toString());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("mensagem", mensagemErroPrefixo + e.getMessage()));
        }

        String nomeSaidaArquivo = trocarExtensao(nomeArquivo, extensaoSaida);

        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(nomeSaidaArquivo).build().toString());
        try {
            response.setContentLengthLong(Files.size(tempSaida));
        } catch (IOException ignored) {
            // segue sem Content-Length; navegador ainda baixa normalmente (chunked)
        }

        try (OutputStream saida = response.getOutputStream()) {
            Files.copy(tempSaida, saida);
        } finally {
            excluirSilenciosamente(tempSaida);
        }

        return null;
    }

    /** Referência de método para {@code convertJsonToExcel}/{@code convertJsonToCsv}: (caminhoEntrada, caminhoSaida) -> void. */
    @FunctionalInterface
    private interface ConversorArquivo {
        void converter(String caminhoEntrada, String caminhoSaida) throws IOException;
    }

    /**
     * Copia um arquivo JSON enviado pelo navegador para {@code backend/src/main/resources},
     * mantendo o nome original do arquivo. Não faz nenhuma conversão — apenas
     * transfere o arquivo, útil para colocar fixtures de teste ao lado de
     * {@code teste.json}/{@code teste_large.json} sem precisar copiar pelo SO/IDE.
     * <p>
     * Sobrescreve silenciosamente se já existir um arquivo com o mesmo nome.
     * Caminho relativo assume que o processo roda com cwd em {@code backend/}
     * (mesma convenção usada pelos defaults de {@link br.com.vivo.ingestao.service.converter.JsonToExcelRunner}).
     * <p>
     * Nota: como {@code src/main/resources} é a árvore-fonte do Maven, o app
     * já em execução não enxerga o arquivo pelo classpath (que aponta para
     * {@code target/classes}) até um novo build/restart.
     */
    @PostMapping("/salvar-json")
    @CrossOrigin(origins = "${app.cors.allowed-origins}")
    public ResponseEntity<?> salvarJsonEmResources(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "Arquivo JSON obrigatorio"));
        }

        String nomeOriginal = file.getOriginalFilename();
        if (nomeOriginal == null || nomeOriginal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "Nome do arquivo invalido"));
        }

        // usa somente o nome do arquivo (descarta qualquer caminho embutido) para evitar path traversal
        String nomeArquivo = Path.of(nomeOriginal).getFileName().toString();
        if (!nomeArquivo.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", "Apenas arquivos .json sao aceitos"));
        }

        try {
            Path destino = resolverArquivoEmResources(nomeArquivo, true);
            file.transferTo(destino);

            return ResponseEntity.ok(Map.of(
                    "mensagem", "Arquivo salvo em " + destino,
                    "arquivo", nomeArquivo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("mensagem", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("mensagem", "Falha ao salvar arquivo: " + e.getMessage()));
        }
    }

    /**
     * Resolve {@code nomeArquivo} dentro de {@code backend/src/main/resources},
     * garantindo que o resultado não escape da pasta (defesa contra path
     * traversal, já mitigado também pelos chamadores via {@code getFileName()}).
     * Caminho relativo assume cwd em {@code backend/}, mesma convenção do
     * {@link br.com.vivo.ingestao.service.converter.JsonToExcelRunner}.
     *
     * @param criarPasta se true, cria {@code src/main/resources} caso não exista
     */
    private static Path resolverArquivoEmResources(String nomeArquivo, boolean criarPasta) throws IOException {
        Path pastaResources = Path.of("src/main/resources").toAbsolutePath().normalize();
        if (criarPasta) {
            Files.createDirectories(pastaResources);
        }

        Path destino = pastaResources.resolve(nomeArquivo).normalize();
        if (!destino.startsWith(pastaResources)) {
            throw new IllegalArgumentException("Nome de arquivo invalido");
        }
        return destino;
    }

    private static String trocarExtensao(String nomeOriginal, String novaExtensao) {
        String base = (nomeOriginal == null || nomeOriginal.isBlank())
                ? "conversao"
                : nomeOriginal.replaceFirst("(?i)\\.json$", "");
        return base + novaExtensao;
    }

    private static void excluirSilenciosamente(Path arquivo) {
        if (arquivo != null) {
            try {
                Files.deleteIfExists(arquivo);
            } catch (IOException ignored) {
                // limpeza best-effort; arquivo temporário órfão não impacta a resposta já enviada
            }
        }
    }
}