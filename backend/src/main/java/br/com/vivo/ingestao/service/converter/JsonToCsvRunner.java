package br.com.vivo.ingestao.service.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-off runner: converts a large JSON file to CSV using streaming.
 *
 * Activated only when the property 'app.csvConverter.enabled=true' is set,
 * so normal Spring Boot startup is NOT affected. Independent from
 * JsonToExcelRunner's 'app.converter.enabled' flag, so either (or both)
 * conversions can be triggered on the same run.
 *
 * Usage:
 *   java -jar app.jar --app.csvConverter.enabled=true \
 *        --app.csvConverter.input=path/to/input.json \
 *        --app.csvConverter.output=path/to/output.csv
 *
 * Defaults (when not specified):
 *   input  = src/main/resources/teste_large.json
 *   output = src/main/resources/teste_csv_new.csv
 */
@Component
@ConditionalOnProperty(name = "app.csvConverter.enabled", havingValue = "true")
public class JsonToCsvRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(JsonToCsvRunner.class);

    private static final String DEFAULT_INPUT  = "src/main/resources/teste_large.json";
    private static final String DEFAULT_OUTPUT = "src/main/resources/teste_csv_new.csv";

    private final JsonToCsvConverter converter;

    public JsonToCsvRunner(JsonToCsvConverter converter) {
        this.converter = converter;
    }

    @Override
    public void run(String... args) throws Exception {
        String inputPath  = DEFAULT_INPUT;
        String outputPath = DEFAULT_OUTPUT;

        // Parse --app.csvConverter.input=... and --app.csvConverter.output=...
        for (String arg : args) {
            if (arg.startsWith("--app.csvConverter.input=")) {
                inputPath = arg.substring("--app.csvConverter.input=".length());
            } else if (arg.startsWith("--app.csvConverter.output=")) {
                outputPath = arg.substring("--app.csvConverter.output=".length());
            }
        }

        Path input  = Path.of(inputPath);
        Path output = Path.of(outputPath);

        if (!Files.exists(input)) {
            log.error("Arquivo JSON não encontrado: {}", input.toAbsolutePath());
            throw new IllegalArgumentException("Input JSON not found: " + input.toAbsolutePath());
        }

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        log.info("=== JsonToCsv Converter ===");
        log.info("Input  : {}", input.toAbsolutePath());
        log.info("Output : {}", output.toAbsolutePath());

        long start = System.currentTimeMillis();
        converter.convertJsonToCsv(inputPath, outputPath);
        long elapsed = System.currentTimeMillis() - start;

        log.info("Conversão concluída em {}ms -> {}", elapsed, output.toAbsolutePath());
    }
}
