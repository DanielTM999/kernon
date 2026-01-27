package dtm.di.prototypes;

import lombok.NonNull;


public interface ComponentActionRegistry<T> {
    /**
     * Retorna a classe de referência associada a essa dependência.
     * <p>
     * É usada pelo container para resolver injeções por tipo.
     *
     * @return Classe de referência do bean.
     */
    @NonNull
    Class<T> getReferenceClass();

    /**
     * Retorna o qualificador associado a essa função de registro.
     * <p>
     * Isso permite que o container diferencie instâncias do mesmo tipo quando necessário.
     *
     * @return Nome do qualificador.
     */
    @NonNull
    String getQualifier();
}
