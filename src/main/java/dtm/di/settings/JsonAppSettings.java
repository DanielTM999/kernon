package dtm.di.settings;

import dtm.di.annotations.aop.DisableAop;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementação JSON padrão de {@link AppSettings} e {@link AppSettingsRegistry}.
 *
 * <p>O estado inicial é carregado do classpath a partir do recurso configurado e dos
 * profiles ativos. Novas fontes podem ser registradas explicitamente em runtime. A
 * política de registro é obtida de {@code settingsRegistry} no estado inicial e não
 * pode ser alterada por fontes externas.</p>
 */
@DisableAop
@Slf4j
public class JsonAppSettings implements AppSettings, AppSettingsRegistry {

    public static final String DEFAULT_RESOURCE_NAME = "settings.json";

    private static final String REGISTRY_PROPERTY = "settingsRegistry";
    private static final String ENABLED_PROPERTY = "enabled";
    private static final String ALLOWED_MODES_PROPERTY = "allowedModes";
    private static final String FAIL_ON_POLICY_OVERRIDE_PROPERTY = "failOnPolicyOverride";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
    private final String resourceName;
    private final List<String> profiles;
    private final boolean registrationEnabled;
    private final Set<SettingsRegistrationMode> allowedModes;
    private final boolean failOnPolicyOverride;

    private ObjectNode root;

    public JsonAppSettings() {
        this(DEFAULT_RESOURCE_NAME);
    }

    public JsonAppSettings(String resourceName) {
        this(resourceName, new String[0]);
    }

    public JsonAppSettings(String resourceName, String... profiles) {
        this.resourceName = Objects.requireNonNull(resourceName, "resourceName não pode ser null");
        this.profiles = normalizeProfiles(profiles);
        this.root = loadFromClasspath(this.profiles);

        RegistryPolicy policy = readRegistryPolicy(root);
        this.registrationEnabled = policy.enabled();
        this.allowedModes = policy.allowedModes();
        this.failOnPolicyOverride = policy.failOnPolicyOverride();
    }

    @Override
    public void register(String json) {
        register(json, SettingsRegistrationMode.KEEP);
    }

    @Override
    public void register(String json, SettingsRegistrationMode mode) {
        ensureModeAllowed(mode);
        registerParsed(parseStrict(json, "conteúdo JSON externo"), mode);
    }

    @Override
    public void register(Path json) {
        register(json, SettingsRegistrationMode.KEEP);
    }

    @Override
    public void register(Path json, SettingsRegistrationMode mode) {
        ensureModeAllowed(mode);
        Objects.requireNonNull(json, "json não pode ser null");
        try {
            registerParsed(parseStrict(Files.readString(json, StandardCharsets.UTF_8), json.toString()), mode);
        } catch (IOException e) {
            throw new SettingsRegistrationException("Falha ao ler o settings externo '" + json + "'.", e);
        }
    }

    @Override
    public void register(Class<?> source) {
        register(source, SettingsRegistrationMode.KEEP);
    }

    @Override
    public void register(Class<?> source, SettingsRegistrationMode mode) {
        ensureModeAllowed(mode);
        Objects.requireNonNull(source, "source não pode ser null");
        registerParsed(loadExternalResources(name -> source.getResourceAsStream('/' + name), source.getName()), mode);
    }

    @Override
    public void register(ClassLoader source) {
        register(source, SettingsRegistrationMode.KEEP);
    }

    @Override
    public void register(ClassLoader source, SettingsRegistrationMode mode) {
        ensureModeAllowed(mode);
        Objects.requireNonNull(source, "source não pode ser null");
        registerParsed(loadExternalResources(source::getResourceAsStream, source.toString()), mode);
    }

    private void registerParsed(ObjectNode external, SettingsRegistrationMode mode) {
        if (external.has(REGISTRY_PROPERTY)) {
            if (failOnPolicyOverride) {
                throw new SettingsRegistrationBlockedException(
                        "A fonte externa tentou alterar a política reservada '" + REGISTRY_PROPERTY + "'."
                );
            }
            external.remove(REGISTRY_PROPERTY);
        }

        if (external.isEmpty()) return;

        stateLock.writeLock().lock();
        try {
            ObjectNode merged = root.deepCopy();
            merge(merged, external, mode);
            root = merged;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    private void ensureModeAllowed(SettingsRegistrationMode mode) {
        Objects.requireNonNull(mode, "mode não pode ser null");
        if (!registrationEnabled) {
            throw new SettingsRegistrationBlockedException("O registro externo de settings está desabilitado.");
        }
        if (!allowedModes.contains(mode)) {
            throw new SettingsRegistrationBlockedException(
                    "O modo de registro " + mode + " não está permitido pelo settings principal."
            );
        }
    }

    private ObjectNode loadExternalResources(ResourceProvider provider, String sourceDescription) {
        ObjectNode settings = loadExternalResource(provider, DEFAULT_RESOURCE_NAME, true, sourceDescription);
        for (String profile : profiles) {
            String profileName = profileResourceName(DEFAULT_RESOURCE_NAME, profile);
            ObjectNode profileSettings = loadExternalResource(provider, profileName, false, sourceDescription);
            if (profileSettings != null) merge(settings, profileSettings, SettingsRegistrationMode.OVERRIDE);
        }
        return settings;
    }

    private ObjectNode loadExternalResource(
            ResourceProvider provider,
            String name,
            boolean required,
            String sourceDescription
    ) {
        try (InputStream in = provider.open(name)) {
            if (in == null) {
                if (!required) return null;
                throw new SettingsRegistrationException(
                        "Recurso obrigatório '" + name + "' não encontrado em " + sourceDescription + "."
                );
            }
            return parseStrict(new String(in.readAllBytes(), StandardCharsets.UTF_8), name);
        } catch (SettingsRegistrationException e) {
            throw e;
        } catch (IOException e) {
            throw new SettingsRegistrationException(
                    "Falha ao ler o recurso externo '" + name + "' de " + sourceDescription + ".",
                    e
            );
        }
    }

    private ObjectNode parseStrict(String content, String sourceDescription) {
        if (content == null || content.isBlank()) {
            throw new SettingsRegistrationException("O settings externo '" + sourceDescription + "' está vazio.");
        }
        try {
            JsonNode node = mapper.readTree(content);
            if (node == null || !node.isObject()) {
                throw new SettingsRegistrationException(
                        "O settings externo '" + sourceDescription + "' deve possuir um objeto JSON na raiz."
                );
            }
            return ((ObjectNode) node).deepCopy();
        } catch (JacksonException e) {
            throw new SettingsRegistrationException(
                    "O settings externo '" + sourceDescription + "' contém JSON inválido.",
                    e
            );
        }
    }

    private ObjectNode loadFromClasspath(List<String> activeProfiles) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = JsonAppSettings.class.getClassLoader();

        ObjectNode settings = loadInitialResource(classLoader, resourceName, true);
        for (String profile : activeProfiles) {
            ObjectNode profileSettings = loadInitialResource(
                    classLoader,
                    profileResourceName(resourceName, profile),
                    false
            );
            if (profileSettings != null) merge(settings, profileSettings, SettingsRegistrationMode.OVERRIDE);
        }
        return settings;
    }

    private ObjectNode loadInitialResource(ClassLoader classLoader, String name, boolean required) {
        try (InputStream in = classLoader.getResourceAsStream(name)) {
            if (in == null) {
                if (!required) return null;
                log.warn("Recurso '{}' não encontrado no classpath. Todas as leituras usarão defaults.", name);
                return JsonNodeFactory.instance.objectNode();
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) return JsonNodeFactory.instance.objectNode();
            JsonNode node = mapper.readTree(content);
            if (node == null || !node.isObject()) {
                log.warn("'{}' não é um objeto JSON. Usando configuração vazia.", name);
                return JsonNodeFactory.instance.objectNode();
            }
            return (ObjectNode) node;
        } catch (IOException | JacksonException e) {
            log.error("Falha ao ler '{}' do classpath: {}. Usando configuração vazia.", name, e.getMessage());
            return JsonNodeFactory.instance.objectNode();
        }
    }

    private RegistryPolicy readRegistryPolicy(ObjectNode settings) {
        JsonNode policy = settings.get(REGISTRY_PROPERTY);
        if (policy == null || !policy.isObject()) {
            return RegistryPolicy.defaults();
        }

        boolean enabled = booleanOrDefault(policy.get(ENABLED_PROPERTY), true);
        boolean failOnOverride = booleanOrDefault(policy.get(FAIL_ON_POLICY_OVERRIDE_PROPERTY), true);
        Set<SettingsRegistrationMode> modes = readAllowedModes(policy.get(ALLOWED_MODES_PROPERTY));
        return new RegistryPolicy(enabled, modes, failOnOverride);
    }

    private Set<SettingsRegistrationMode> readAllowedModes(JsonNode configuredModes) {
        if (configuredModes == null || !configuredModes.isArray() || configuredModes.isEmpty()) {
            return Set.of(SettingsRegistrationMode.KEEP);
        }

        EnumSet<SettingsRegistrationMode> modes = EnumSet.noneOf(SettingsRegistrationMode.class);
        for (JsonNode configuredMode : configuredModes) {
            if (!configuredMode.isString()) return Set.of(SettingsRegistrationMode.KEEP);
            String value = configuredMode.asText();
            try {
                modes.add(SettingsRegistrationMode.valueOf(value));
            } catch (IllegalArgumentException e) {
                log.warn("Modo desconhecido '{}' em {}.{}. Usando [KEEP].",
                        value, REGISTRY_PROPERTY, ALLOWED_MODES_PROPERTY);
                return Set.of(SettingsRegistrationMode.KEEP);
            }
        }
        return modes.isEmpty() ? Set.of(SettingsRegistrationMode.KEEP) : Set.copyOf(modes);
    }

    private boolean booleanOrDefault(JsonNode node, boolean defaultValue) {
        return node != null && node.isBoolean() ? node.asBoolean() : defaultValue;
    }

    private String profileResourceName(String baseResourceName, String profile) {
        int separator = baseResourceName.lastIndexOf('/');
        int extension = baseResourceName.lastIndexOf('.');
        if (extension <= separator) return baseResourceName + "." + profile;
        return baseResourceName.substring(0, extension)
                + "." + profile
                + baseResourceName.substring(extension);
    }

    private List<String> normalizeProfiles(String... configuredProfiles) {
        if (configuredProfiles == null || configuredProfiles.length == 0) return List.of();
        return Arrays.stream(configuredProfiles)
                .filter(Objects::nonNull)
                .flatMap(profile -> Arrays.stream(profile.split(",")))
                .map(String::trim)
                .filter(profile -> !profile.isEmpty())
                .distinct()
                .toList();
    }

    private void merge(ObjectNode target, ObjectNode source, SettingsRegistrationMode mode) {
        for (Map.Entry<String, JsonNode> property : source.properties()) {
            String name = property.getKey();
            JsonNode value = property.getValue();
            JsonNode current = target.get(name);
            if (current != null && current.isObject() && value.isObject()) {
                merge((ObjectNode) current, (ObjectNode) value, mode);
            } else if (current == null || mode == SettingsRegistrationMode.OVERRIDE) {
                target.set(name, value.deepCopy());
            }
        }
    }

    @Override
    public String getString(String key, String defaultValue) {
        JsonNode element = lookupSnapshot(key);
        if (isAbsent(element)) return defaultValue;
        return element.isValueNode() ? element.asText() : element.toString();
    }

    @Override
    public int getInt(String key, int defaultValue) {
        JsonNode element = lookupSnapshot(key);
        if (isAbsent(element)) return defaultValue;
        try { return element.asInt(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public long getLong(String key, long defaultValue) {
        JsonNode element = lookupSnapshot(key);
        if (isAbsent(element)) return defaultValue;
        try { return element.asLong(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public double getDouble(String key, double defaultValue) {
        JsonNode element = lookupSnapshot(key);
        if (isAbsent(element)) return defaultValue;
        try { return element.asDouble(defaultValue); } catch (Exception e) { return defaultValue; }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        JsonNode element = lookupSnapshot(key);
        if (isAbsent(element)) return defaultValue;
        try {
            if (element.isBoolean()) return element.asBoolean();
            return Boolean.parseBoolean(element.asText());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @Override
    public <T> T getObject(String key, Class<T> type) {
        JsonNode element = lookupSnapshot(key);
        if (!isAbsent(element)) {
            try {
                return mapper.treeToValue(element, type);
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
        JsonNode element = lookupSnapshot(key);
        if (!isAbsent(element)) {
            try {
                JavaType javaType = mapper.getTypeFactory().constructType(type);
                return mapper.convertValue(element, javaType);
            } catch (Exception e) {
                log.warn("Falha ao desserializar {} como {}: {}. Usando instancia default.",
                        key, type.getTypeName(), e.getMessage());
            }
        }
        return (T) defaultInstance(rawClass(type));
    }

    @Override
    public boolean has(String key) {
        JsonNode element = lookupSnapshot(key);
        return element != null && !element.isMissingNode();
    }

    public String[] getStringArray(String key) {
        JsonNode element = lookupSnapshot(key);
        if (isAbsent(element)) return new String[0];
        if (element.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : element) {
                if (!isAbsent(item)) values.add(item.asText());
            }
            return values.toArray(String[]::new);
        }
        return new String[]{element.asText()};
    }

    private boolean isAbsent(JsonNode element) {
        return element == null || element.isNull() || element.isMissingNode();
    }

    private JsonNode lookupSnapshot(String key) {
        if (key == null || key.isEmpty()) return null;
        stateLock.readLock().lock();
        try {
            String[] parts = key.split("\\.");
            JsonNode current = root;
            for (String part : parts) {
                if (current == null || !current.isObject()) return null;
                current = current.get(part);
                if (current == null) return null;
            }
            return current.deepCopy();
        } finally {
            stateLock.readLock().unlock();
        }
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

    @FunctionalInterface
    private interface ResourceProvider {
        InputStream open(String resourceName);
    }

    private record RegistryPolicy(
            boolean enabled,
            Set<SettingsRegistrationMode> allowedModes,
            boolean failOnPolicyOverride
    ) {
        private static RegistryPolicy defaults() {
            return new RegistryPolicy(true, Set.of(SettingsRegistrationMode.KEEP), true);
        }
    }
}
