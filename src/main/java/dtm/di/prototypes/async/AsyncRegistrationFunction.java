package dtm.di.prototypes.async;

import dtm.di.prototypes.RegistrationFunction;

import java.util.concurrent.ExecutorService;

public interface AsyncRegistrationFunction<T> extends RegistrationFunction<T> {

    ExecutorService getExecutor();

}
