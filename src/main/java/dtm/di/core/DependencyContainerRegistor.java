package dtm.di.core;

import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.prototypes.RegistrationFunction;
import lombok.NonNull;

import java.util.function.Supplier;

public interface DependencyContainerRegistor {
    void registerDependency(Object dependency, String qualifier) throws InvalidClassRegistrationException;
    void registerDependency(Object dependency) throws InvalidClassRegistrationException;
    <T> void registerDependency(RegistrationFunction<T> registrationFunction) throws InvalidClassRegistrationException;
    void unRegisterDependency(Class<?> dependency);

    static <T> RegistrationFunction<T> ofAction(@NonNull Class<T> reference, @NonNull Supplier<T> action){
        return ofAction(reference, action, "default");
    }

    static <T> RegistrationFunction<T> ofAction(@NonNull Class<T> reference, @NonNull Supplier<T> action, @NonNull String qualifier){
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
}
