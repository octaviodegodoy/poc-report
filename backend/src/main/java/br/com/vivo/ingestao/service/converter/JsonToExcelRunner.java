package br.com.vivo.ingestao.service.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-off runner: converts a large JSON file to Excel using streaming.
 *
 * Activated only when the property 'app.converter.enabled=true' is set,
 * so normal Spring Boot startup is NOT affected.
 *
 * Usage:
 *   java -jar app.jar --app.converter.enabled=true \
 *        --app.converter.input=path/to/input.json \
 *        --app.converter.output=path/to/output.xlsx
 *
 * Defaults (when not specified):
 *   input  = src/main/resources/teste.json
 *   output = src/main/resources/teste_excel_new.xlsx
 */
@Component
@ConditionalOnProperty(name = "app.converter.enabled", havingValue = "true")
public class JsonToExcelRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(JsonToExcelRunner.class);

    private static final String DEFAULT_INPUT  = "src/main/resources/teste_large.json";
    private static final String DEFAULT_OUTPUT = "src/main/resources/teste_excel_new.xlsx";

    private final JsonToExcelConverter converter;

    public JsonToExcelRunner(JsonToExcelConverter converter) {
        this.converter = converter;
    }

    @Override
    public void run(String... args) throws Exception {
        String inputPath  = DEFAULT_INPUT;
        String outputPath = DEFAULT_OUTPUT;

        // Parse --app.converter.input=... and --app.converter.output=...
        for (String arg : args) {
            if (arg.startsWith("--app.converter.input=")) {
                inputPath = arg.substring("--app.converter.input=".length());
            } else if (arg.startsWith("--app.converter.output=")) {
                outputPath = arg.substring("--app.converter.output=".length());
            }
        }

        Path input  = Path.of(inputPath);
        Path output = Path.of(outputPath);

        if (!Files.exists(input)) {
            log.error("Arquivo JSON não encontrado: {}", input.toAbsolutePath());
            throw new IllegalArgumentException("Input JSON not found: " + input.toAbsolutePath());
        }

        // Create output directories if needed
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        log.info("=== JsonToExcel Converter ===");
        log.info("Input  : {}", input.toAbsolutePath());
        log.info("Output : {}", output.toAbsolutePath());

        long start = System.currentTimeMillis();
        converter.convertJsonToExcel(inputPath, outputPath);
        long elapsed = System.currentTimeMillis() - start;

        log.info("Conversão concluída em {}ms -> {}", elapsed, output.toAbsolutePath());
    }
}

