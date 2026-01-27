package dtm.di.prototypes;

import lombok.NonNull;

import java.util.function.Supplier;

/**
 * Representa um registro especial no container, no qual uma função (supplier) é usada
 * para fornecer a instância da dependência quando necessário.
 * <p>
 * Ao invés de registrar uma classe concreta para instanciação automática,
 * o {@code RegistrationFunction} permite que o desenvolvedor registre uma função lambda,
 * método ou factory que será chamado sempre que a dependência for solicitada.
 * <p>
 * Útil para:
 * <ul>
 *     <li>Beans dinâmicos</li>
 *     <li>Provedores de contexto</li>
 *     <li>Configurações mutáveis</li>
 *     <li>Integração com sistemas externos ou factories customizadas</li>
 * </ul>
 *
 * <p> Esse mecanismo foge do padrão de criação automática por construtor, oferecendo controle total
 * sobre como a instância será construída.</p>
 *
 * @param <T> Tipo da dependência fornecida pela função.
 */
public interface RegistrationFunction<T> extends ComponentActionRegistry<T>{

    /**
     * Retorna a função (supplier) responsável por fornecer a instância da dependência.
     * <p>
     * Sempre que o container solicitar essa dependência, essa função será executada
     * para produzir a instância.
     *
     * @return Supplier que gera a instância.
     */
    @NonNull
    Supplier<T> getFunction();

}
