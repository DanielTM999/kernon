package dtm.di.common;

import dtm.di.prototypes.RegistrationFunction;
import dtm.di.prototypes.async.AsyncRegistrationFunction;
import lombok.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public final class ComponentRegistor {

    /**
     * Cria uma função de registro padrão a partir de uma classe de referência e uma função Supplier.
     * A qualificadora padrão é "default".
     *
     * @param <T>       tipo da dependência
     * @param reference classe da dependência
     * @param action    Supplier que cria a instância da dependência
     * @return função de registro pronta para uso
     */
    public static <T> RegistrationFunction<T> ofAction(@NonNull Class<T> reference, @NonNull Supplier<T> action){
        return ofAction(reference, action, "default");
    }

    /**
     * Cria uma função de registro a partir de uma classe de referência, uma função Supplier e uma qualificadora.
     *
     * @param <T>       tipo da dependência
     * @param reference classe da dependência
     * @param action    Supplier que cria a instância da dependência
     * @param qualifier qualificadora para diferenciar múltiplas implementações
     * @return função de registro pronta para uso
     */
    public static <T> RegistrationFunction<T> ofAction(@NonNull Class<T> reference, @NonNull Supplier<T> action, @NonNull String qualifier){
        return new RegistrationFunction<T>() {

            @NonNull
            @Override
            public Supplier<T> getFunction() {
                return action;
            }

            @NonNull
            @Override
            public Class<T> getReferenceClass() {
                return reference;
            }

            @NonNull
            @Override
            public String getQualifier() {
                return (qualifier.isEmpty()) ? "default" : qualifier;
            }

        };
    }


    public static <T> AsyncRegistrationFunction<T> ofAsync(@NonNull Class<T> reference, @NonNull Supplier<T> action){
        return ofAsync(reference, action, "default", null);
    }

    public static <T> AsyncRegistrationFunction<T> ofAsync(@NonNull Class<T> reference, @NonNull Supplier<T> action, ExecutorService executorService){
        return ofAsync(reference, action, "default", executorService);
    }

    public static <T> AsyncRegistrationFunction<T> ofAsync(@NonNull Class<T> reference, @NonNull Supplier<T> action, String qualifier){
        return ofAsync(reference, action, qualifier, null);
    }

    public static <T> AsyncRegistrationFunction<T> ofAsync(@NonNull Class<T> reference, @NonNull Supplier<T> action, @NonNull String qualifier, ExecutorService executorService){
        return new AsyncRegistrationFunction<T>() {
            @Override
            public ExecutorService getExecutor() {
                return executorService;
            }

            @Override
            public @NonNull Supplier<T> getFunction() {
                return action;
            }

            @Override
            public @NonNull Class<T> getReferenceClass() {
                return reference;
            }

            @Override
            public @NonNull String getQualifier() {
                return qualifier;
            }
        };
    }

}
