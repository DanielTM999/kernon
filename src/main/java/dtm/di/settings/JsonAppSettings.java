package dtm.di.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dtm.di.annotations.aop.DisableAop;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementação padrão de {@link AppSettings} ancorada em um arquivo {@code settings.json}.
 *
 * <h3>Fonte de leitura</h3>
 * <ol>
 *   <li>{@code ./settings.json} no diretório de trabalho — fonte primária e gravável.</li>
 *   <li>Se o arquivo não existir, o framework tenta copiar de {@code /settings.json}
 *       no classpath (se algum recurso embarcado existir).</li>
 *   <li>Se nada existir, é criado um arquivo vazio {@code &#123;&#125;}.</li>
 * </ol>
 *
 * <h3>Concorrência</h3>
 * <p>Leitura paralela via {@link ReentrantReadWriteLock}. {@code set()} grava
 * sob lock de escrita.</p>
 */
@DisableAop
@Slf4j
public class JsonAppSettings implements AppSettings {

    public static final String DEFAULT_FILE_NAME = "settings.json";

    private final Path filePath;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private ObjectNode root;

    public JsonAppSettings() {
        this(Paths.get(DEFAULT_FILE_NAME));
    }

    public JsonAppSettings(Path filePath) {
        this.filePath = filePath;
        this.root = loadOrCreate();
    }

    private ObjectNode loadOrCreate() {
        try {
            if (Files.exists(filePath)) {
                return parse(Files.readString(filePath, StandardCharsets.UTF_8));
            }

            ObjectNode seeded = readClasspathSeed();
            ObjectNode content = (seeded != null) ? seeded : JsonNodeFactory.instance.objectNode();
            persist(content);
            log.info("settings.json criado em {}", filePath.toAbsolutePath());
            return content;
        } catch (Exception e) {
            log.error("Falha ao carregar {}: {}. Usando configuração vazia.", filePath, e.getMessage());
            return JsonNodeFactory.instance.objectNode();
        }
    }

    private ObjectNode readClasspathSeed() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(DEFAULT_FILE_NAME)) {
            if (in == null) return null;
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(content);
        } catch (IOException e) {
            log.warn("Falha ao ler {} do classpath: {}", DEFAULT_FILE_NAME, e.getMessage());
            return null;
        }
    }

    private ObjectNode parse(String content) {
        try {
            if (content == null || content.isBlank()) return JsonNodeFactory.instance.objectNode();
            JsonNode node = mapper.readTree(content);
            return (node != null && node.isObject()) ? (ObjectNode) node : JsonNodeFactory.instance.objectNode();
        } catch (IOException e) {
            log.error("settings.json inválido: {}. Resetando para vazio.", e.getMessage());
            return JsonNodeFactory.instance.objectNode();
        }
    }

    private void persist(ObjectNode content) {
        try {
            if (filePath.getParent() != null) Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(content), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Falha ao gravar {}: {}", filePath, e.getMessage(), e);
        }
    }

    @Override
    public String getString(String key, String defaultValue) {
        JsonNode el = lookup(key);
        if (el == null || el.isNull() || el.isMissingNode()) return defaultValue;
        return el.isValueNode() ? el.asText() : el.toString();
    }

    @Override
    public int getInt(String key, int defaultValue) {
        JsonNode el = lookup(key);
        if (el == null || el.isNull() || el.isMissingNode()) return defaultValue;
        try { return el.asInt(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public long getLong(String key, long defaultValue) {
        JsonNode el = lookup(key);
        if (el == null || el.isNull() || el.isMissingNode()) return defaultValue;
        try { return el.asLong(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public double getDouble(String key, double defaultValue) {
        JsonNode el = lookup(key);
        if (el == null || el.isNull() || el.isMissingNode()) return defaultValue;
        try { return el.asDouble(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        JsonNode el = lookup(key);
        if (el == null || el.isNull() || el.isMissingNode()) return defaultValue;
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
        if (el != null && !el.isNull() && !el.isMissingNode()) {
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
    public boolean has(String key) {
        JsonNode el = lookup(key);
        return el != null && !el.isMissingNode();
    }

    @Override
    public void set(String key, Object value) {
        lock.writeLock().lock();
        try {
            JsonNode el = (value == null) ? null : mapper.valueToTree(value);
            assign(root, key, el);
            persist(root);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private JsonNode lookup(String key) {
        if (key == null || key.isEmpty()) return null;
        lock.readLock().lock();
        try {
            String[] parts = key.split("\\.");
            JsonNode current = root;
            for (String part : parts) {
                if (current == null || !current.isObject()) return null;
                current = current.get(part);
                if (current == null) return null;
            }
            return current;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void assign(ObjectNode node, String key, JsonNode value) {
        String[] parts = key.split("\\.");
        ObjectNode current = node;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = current.get(parts[i]);
            if (child == null || !child.isObject()) {
                ObjectNode created = JsonNodeFactory.instance.objectNode();
                current.set(parts[i], created);
                current = created;
            } else {
                current = (ObjectNode) child;
            }
        }
        if (value == null) {
            current.remove(parts[parts.length - 1]);
        } else {
            current.set(parts[parts.length - 1], value);
        }
    }

    private <T> T defaultInstance(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            if (!constructor.canAccess(null)) constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            log.warn("Sem construtor default para {} — retornando null", type.getName());
            return null;
        }
    }
}
