package io.github.santhosh2013.supportsense.triage.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/** Loads versioned prompt templates from classpath resources, never inline Java strings. */
@Component
public class PromptResourceLoader {

    private final ResourceLoader resourceLoader;

    public PromptResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String loadAndRender(String templateName, Map<String, String> values) {
        String template = load(templateName);
        String rendered = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            rendered = rendered.replace("{" + value.getKey() + "}", value.getValue());
        }
        return rendered;
    }

    public String load(String templateName) {
        try {
            var resource = resourceLoader.getResource("classpath:prompts/" + templateName + ".st");
            if (!resource.exists()) {
                throw new IllegalArgumentException("Prompt template not found: " + templateName);
            }
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load prompt template: " + templateName, e);
        }
    }
}
