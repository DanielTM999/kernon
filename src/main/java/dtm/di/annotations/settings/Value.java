package dtm.di.annotations.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injeção declarativa de valor vindo do {@code settings.json}.
 *
 * <p>Análogo ao {@code @Value} do Spring. A chave é lida do {@link dtm.di.settings.AppSettings}
 * e convertida automaticamente para o tipo do campo:</p>
 *
 * <ul>
 *   <li><b>Primitivos / String</b>: convertidos diretamente. Se a chave não existir,
 *       usa {@link #defaultValue()} (string parsada para o tipo do campo).</li>
 *   <li><b>Objeto qualquer</b>: desserializado via Gson. Se ausente ou inválido,
 *       o framework cria uma nova instância via construtor default.</li>
 * </ul>
 *
 * <pre>{@code
 * @Service
 * public class DbService {
 *
 *     @Value(key = "db.url", defaultValue = "jdbc:h2:mem:default")
 *     private String url;
 *
 *     @Value(key = "db.maxConnections", defaultValue = "10")
 *     private int maxConnections;
 *
 *     @Value(key = "db")            // objeto inteiro
 *     private DbConfig config;
 * }
 * }</pre>
 *
 * <p>Não precisa combinar com {@code @Inject} — a presença de {@code @Value} é
 * suficiente para o framework resolver o campo.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface Value {
    /**
     * Caminho da chave no settings.json, em notação de ponto. Ex.: {@code "text.val"}.
     */
    String key();

    /**
     * Valor default em forma de string. Aplicado para tipos primitivos/String quando a
     * chave não existe. Ignorado para objetos (que usam construtor default como fallback).
     */
    String defaultValue() default "";
}
