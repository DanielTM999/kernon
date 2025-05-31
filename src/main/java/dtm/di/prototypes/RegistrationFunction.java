package dtm.di.prototypes;

import lombok.NonNull;

import java.util.function.Supplier;

public interface RegistrationFunction<T> {
    @NonNull
    Supplier<T> getFunction();

    @NonNull
    Class<T> getReferenceClass();

    @NonNull
    String getQualifier();
}
