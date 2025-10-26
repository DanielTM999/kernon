package dtm.di.prototypes;

import dtm.di.startup.ManagedApplicationStartup;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public final class ControllerAdviceUtilities {

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

    public static void callOnLoad(ThrowableAction throwableAction){
        EXECUTOR_SERVICE.execute(() -> {
            while(true){
                Thread.UncaughtExceptionHandler exceptionHandle = Thread.getDefaultUncaughtExceptionHandler();
                if (exceptionHandle != null) {
                    Class<?> handlerClass = exceptionHandle.getClass();
                    boolean isLambda = handlerClass.isSynthetic() &&
                            handlerClass.getName().contains("$$Lambda");

                    if (isLambda && handlerClass.getName().contains(ManagedApplicationStartup.class.getName())) {
                        try {
                            throwableAction.run();
                            break;
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Throwable e) {
                            throw new CompletionException(e);
                        }
                    }
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

}
