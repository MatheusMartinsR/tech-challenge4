package br.com.fiap.tc4.notification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

/** Lê o template existente no SAM sem construir objetos a partir das tags CloudFormation. */
record LocalSesTemplate(String name, String subject, String text, String html) {
    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    static LocalSesTemplate load(Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            Node root = new Yaml().compose(reader);
            Node template = child(child(child(child(root, "Resources"), "CriticalFeedbackEmailTemplate"),
                    "Properties"), "Template");
            return new LocalSesTemplate(scalar(template, "TemplateName").replace("${Stage}", "local"),
                    scalar(template, "SubjectPart"), scalar(template, "TextPart"), scalar(template, "HtmlPart"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read local SAM email template", e);
        }
    }

    private static Node child(Node node, String key) {
        if (node instanceof MappingNode mapping) {
            for (var entry : mapping.getValue()) {
                if (entry.getKeyNode() instanceof ScalarNode name && key.equals(name.getValue())) {
                    return entry.getValueNode();
                }
            }
        }
        throw new IllegalArgumentException("Missing SAM template field: " + key);
    }

    private static String scalar(Node node, String key) {
        if (child(node, key) instanceof ScalarNode value) return value.getValue();
        throw new IllegalArgumentException("Expected scalar SAM field: " + key);
    }

    /** Subconjunto de placeholders usado pelo template atual; não interpreta conteúdo dos dados. */
    static String render(String source, Map<String, String> data, boolean html) {
        if (source.contains("{{{")) throw new IllegalArgumentException("Unescaped template expressions are unsupported");
        var matcher = FIELD.matcher(source);
        return matcher.replaceAll(match -> {
            String value = data.get(match.group(1));
            if (value == null) throw new IllegalArgumentException("Missing email template value: " + match.group(1));
            if (html) value = value.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;")
                    .replace("`", "&#x60;").replace("=", "&#x3D;");
            return java.util.regex.Matcher.quoteReplacement(value);
        });
    }
}
