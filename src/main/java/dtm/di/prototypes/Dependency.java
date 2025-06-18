package dtm.di.prototypes;

import java.util.List;

/**
 * Representa uma dependência registrada no container de injeção.
 * <p>
 * Essa abstração permite acessar diretamente o bean gerenciado pelo framework,
 * junto com seus metadados como:
 * <ul>
 *     <li>Se é singleton ou uma nova instância por injeção</li>
 *     <li>A instância concreta (se for singleton ou já instanciada)</li>
 *     <li>O tipo principal da dependência</li>
 *     <li>O qualificador usado na resolução</li>
 *     <li>Os tipos adicionais compatíveis com essa dependência (interfaces ou superclasses)</li>
 * </ul>
 * <p>
 * Isso é o que o container pode fornecer como o "bean bruto", caso o desenvolvedor precise
 * de acesso direto à instância, sem passar por proxy ou outros mecanismos de ciclo de vida.
 * <p>
 * Normalmente utilizado internamente, mas pode ser exposto em APIs avançadas como ServiceLocator,
 * Debugger de dependências, ou inspeção dinâmica de registros no container.
 */
public abstract class Dependency {
    public abstract boolean isSingleton();
    public abstract Object getDependency();
    public abstract Class<?> getDependencyClass();
    public abstract String getQualifier();
    public abstract List<Class<?>> getDependencyClassInstanceTypes();
}
