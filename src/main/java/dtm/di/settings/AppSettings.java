package dtm.di.settings;

/**
 * Acesso programático às configurações do {@code settings.json} — análogo ao
 * {@code IConfiguration} do C# / {@code Environment} do Spring.
 *
 * <p>Chaves usam notação de ponto para navegar em estruturas aninhadas.
 * Exemplo, dado o JSON:</p>
 * <pre>{@code
 * {
 *   "text": { "val": 1 },
 *   "db":   { "url": "jdbc:..." }
 * }
 * }</pre>
 *
 * <p>Lê com:</p>
 * <pre>{@code
 * int v   = settings.getInt("text.val", 0);
 * String url = settings.getString("db.url", "jdbc:default");
 * MyConfig cfg = settings.getObject("db", MyConfig.class);
 * }</pre>
 *
 * <h3>Comportamento de fallback</h3>
 * <ul>
 *   <li>Tipos primitivos: se a chave não existir, retorna o {@code defaultValue} fornecido.</li>
 *   <li>{@link #getObject(String, Class)}: se a chave não existir OU der erro de
 *       desserialização, retorna uma nova instância via construtor default da classe.
 *       Se a classe não tiver construtor default acessível, retorna {@code null}.</li>
 * </ul>
 *
 * <p>Implementações são thread-safe para leitura.</p>
 */
public interface AppSettings {

    String getString(String key, String defaultValue);

    int getInt(String key, int defaultValue);

    long getLong(String key, long defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    double getDouble(String key, double defaultValue);

    /**
     * Recupera uma chave como objeto desserializado.
     * Se ausente ou inválido, retorna uma nova instância via construtor default.
     */
    <T> T getObject(String key, Class<T> type);

    /**
     * Indica se a chave existe (mesmo que com valor {@code null}).
     */
    boolean has(String key);

    /**
     * Persiste um valor (primitivo ou objeto) no arquivo de configuração.
     * Usado raramente — a fonte de verdade é o JSON em disco editado pelo usuário.
     */
    void set(String key, Object value);
}
