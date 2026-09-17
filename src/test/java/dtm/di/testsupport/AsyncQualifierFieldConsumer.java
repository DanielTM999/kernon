package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.Singleton;
import dtm.di.prototypes.async.AsyncComponent;

@Singleton
@Component
@Profile("async-qualifier")
public class AsyncQualifierFieldConsumer {

    @Inject
    @Qualifier("special")
    private AsyncComponent<AsyncPayload> special;

    @Inject
    private AsyncComponent<AsyncPayload> byDefault;

    public AsyncComponent<AsyncPayload> special() {
        return special;
    }

    public AsyncComponent<AsyncPayload> byDefault() {
        return byDefault;
    }
}
