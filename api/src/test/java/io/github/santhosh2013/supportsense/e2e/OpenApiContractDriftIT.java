package io.github.santhosh2013.supportsense.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.santhosh2013.supportsense.support.PostgresTestContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Structural drift check between the hand-maintained {@code src/main/resources/openapi.yaml}
 * (the authority, per its own header comment) and the springdoc-generated spec fetched live
 * from {@code /v3/api-docs}. Compares only path+method presence in both directions — not a
 * full schema diff, per the mission brief's "structural comparison" scope.
 *
 * <p>YAML parsing uses {@code jackson-dataformat-yaml}, already present transitively via
 * springdoc-openapi-starter-webmvc-ui (confirmed on the classpath via {@code mvn
 * dependency:tree} before writing this — no new dependency was added).
 *
 * <p>{@code /v3/api-docs} is permitted without auth in {@code SecurityConfig} (the bare path
 * is listed explicitly alongside {@code /v3/api-docs/**}, addressing the exact
 * bare-path-vs-wildcard gap the mission brief called out) — verified by reading
 * {@code SecurityConfig.java} directly; this test also implicitly re-verifies it by fetching
 * with no Authorization header and asserting 200.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class OpenApiContractDriftIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("every path+method in the hand-maintained openapi.yaml exists in the live /v3/api-docs, and vice versa")
    void handMaintainedSpecMatchesGeneratedSpec() throws Exception {
        // No Authorization header at all — also proves /v3/api-docs itself is public.
        ResponseEntity<String> generatedResponse =
                restTemplate.getForEntity(url("/v3/api-docs"), String.class);
        assertThat(generatedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ObjectMapper jsonMapper = new ObjectMapper();
        JsonNode generatedSpec = jsonMapper.readTree(generatedResponse.getBody());
        Set<String> generatedPathMethods = extractPathMethods(generatedSpec);

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        Path handMaintainedSpecPath =
                Path.of("src/main/resources/openapi.yaml").toAbsolutePath();
        assertThat(Files.exists(handMaintainedSpecPath))
                .as("hand-maintained openapi.yaml must exist at %s", handMaintainedSpecPath)
                .isTrue();
        JsonNode handMaintainedSpec = yamlMapper.readTree(Files.readString(handMaintainedSpecPath));
        Set<String> handMaintainedPathMethods = extractPathMethods(handMaintainedSpec);

        Set<String> missingFromGenerated = new TreeSet<>(handMaintainedPathMethods);
        missingFromGenerated.removeAll(generatedPathMethods);

        Set<String> missingFromHandMaintained = new TreeSet<>(generatedPathMethods);
        missingFromHandMaintained.removeAll(handMaintainedPathMethods);

        assertThat(missingFromGenerated)
                .as("documented in openapi.yaml but not implemented (missing from generated /v3/api-docs)")
                .isEmpty();
        assertThat(missingFromHandMaintained)
                .as("implemented (present in generated /v3/api-docs) but not documented in openapi.yaml")
                .isEmpty();
    }

    /** Extracts a normalized "METHOD path" set from an OpenAPI document's `paths` node. */
    private static Set<String> extractPathMethods(JsonNode spec) {
        Set<String> result = new TreeSet<>();
        JsonNode paths = spec.get("paths");
        if (paths == null) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> pathEntries = paths.fields();
        while (pathEntries.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathEntries.next();
            String path = pathEntry.getKey();
            Iterator<Map.Entry<String, JsonNode>> methodEntries = pathEntry.getValue().fields();
            while (methodEntries.hasNext()) {
                String method = methodEntries.next().getKey();
                if (isHttpMethod(method)) {
                    result.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path);
                }
            }
        }
        return result;
    }

    private static boolean isHttpMethod(String key) {
        return switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "get", "post", "put", "delete", "patch", "options", "head", "trace" -> true;
            default -> false;
        };
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
