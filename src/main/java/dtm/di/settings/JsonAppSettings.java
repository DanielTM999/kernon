package dtm.di.settings;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import dtm.di.annotations.aop.DisableAop;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementação padrão de {@link AppSettings}, somente leitura, ancorada em um
 * recurso JSON do classpath ({@code src/main/resources/settings.json} no projeto,
 * empacotado no JAR em build).
 *
 * <p>Comportamento na inicialização:</p>
 * <ol>
 *   <li>Procura {@code /settings.json} no classpath via context class loader.</li>
 *   <li>Se encontrado, faz parse e mantém em memória.</li>
 *   <li>Se ausente ou malformado, usa um objeto JSON vazio e loga warning —
 *       todas as leituras retornarão o {@code defaultValue} fornecido.</li>
 * </ol>
 *
 * <p>Como o conteúdo é imutável após o boot, não há necessidade de locks de leitura.</p>
 */
@DisableAop
@Slf4j
public class JsonAppSettings implements AppSettings {

    public static final String DEFAULT_RESOURCE_NAME = "settings.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode root;
    private final String resourceName;

    public JsonAppSettings() {
        this(DEFAULT_RESOURCE_NAME);
    }

    public JsonAppSettings(String resourceName) {
        this.resourceName = resourceName;
        this.root = loadFromClasspath();
    }

    private ObjectNode loadFromClasspath() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = JsonAppSettings.class.getClassLoader();

        try (InputStream in = cl.getResourceAsStream(resourceName)) {
            if (in == null) {
                log.warn("Recurso '{}' não encontrado no classpath. Todas as leituras usarão defaults.", resourceName);
                return JsonNodeFactory.instance.objectNode();
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) return JsonNodeFactory.instance.objectNode();
            JsonNode node = mapper.readTree(content);
            if (node == null || !node.isObject()) {
                log.warn("'{}' não é um objeto JSON. Usando configuração vazia.", resourceName);
                return JsonNodeFactory.instance.objectNode();
            }
            return (ObjectNode) node;
        } catch (IOException | JacksonException e) {
            log.error("Falha ao ler '{}' do classpath: {}. Usando configuração vazia.", resourceName, e.getMessage());
            return JsonNodeFactory.instance.objectNode();
        }
    }

    @Override
    public String getString(String key, String defaultValue) {
        JsonNode el = lookup(key);
        if (isAbsent(el)) return defaultValue;
        return el.isValueNode() ? el.asText() : el.toString();
    }

    @Override
    public int getInt(String key, int defaultValue) {
        JsonNode el = lookup(key);
        if (isAbsent(el)) return defaultValue;
        try { return el.asInt(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public long getLong(String key, long defaultValue) {
        JsonNode el = lookup(key);
        if (isAbsent(el)) return defaultValue;
        try { return el.asLong(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public double getDouble(String key, double defaultValue) {
        JsonNode el = lookup(key);
        if (isAbsent(el)) return defaultValue;
        try { return el.asDouble(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        JsonNode el = lookup(key);
        if (isAbsent(el)) return defaultValue;
        try {
            if (el.isBoolean()) return el.asBoolean();
            return Boolean.parseBoolean(el.asText());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @Override
    public <T> T getObject(String key, Class<T> type) {
        JsonNode el = lookup(key);
        if (!isAbsent(el)) {
            try {
                return mapper.treeToValue(el, type);
            } catch (Exception e) {
                log.warn("Falha ao desserializar {} como {}: {}. Usando instância default.",
                        key, type.getName(), e.getMessage());
            }
        }
        return defaultInstance(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(String key, Type type) {
        JsonNode el = lookup(key);
        if (!isAbsent(el)) {
            try {
                JavaType javaType = mapper.getTypeFactory().constructType(type);
                return mapper.convertValue(el, javaType);
            } catch (Exception e) {
                log.warn("Falha ao desserializar {} como {}: {}. Usando instancia default.",
                        key, type.getTypeName(), e.getMessage());
            }
        }
        return (T) defaultInstance(rawClass(type));
    }

    @Override
    public boolean has(String key) {
        JsonNode el = lookup(key);
        return el != null && !el.isMissingNode();
    }

    public String[] getStringArray(String key) {
        JsonNode el = lookup(key);
        if (isAbsent(el)) return new String[0];
        if (el.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : el) {
                if (!isAbsent(item)) {
                    values.add(item.asText());
                }
            }
            return values.toArray(String[]::new);
        }
        return new String[]{el.asText()};
    }

    private boolean isAbsent(JsonNode el) {
        return el == null || el.isNull() || el.isMissingNode();
    }

    private JsonNode lookup(String key) {
        if (key == null || key.isEmpty()) return null;
        String[] parts = key.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || !current.isObject()) return null;
            current = current.get(part);
            if (current == null) return null;
        }
        return current;
    }

    private <T> T defaultInstance(Class<T> type) {
        if (type == null) return null;
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            if (!constructor.canAccess(null)) constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            log.warn("Sem construtor default para {} — retornando null", type.getName());
            return null;
        }
    }

    private Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }
}
