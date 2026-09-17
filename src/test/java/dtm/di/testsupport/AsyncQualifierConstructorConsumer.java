package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.Singleton;
import dtm.di.prototypes.async.AsyncComponent;

@Singleton
@Component
@Profile("async-qualifier")
public class AsyncQualifierConstructorConsumer {

    private final AsyncComponent<AsyncPayload> special;
    private final AsyncComponent<AsyncPayload> byDefault;

    public AsyncQualifierConstructorConsumer(
            @Qualifier("special") AsyncComponent<AsyncPayload> special,
            AsyncComponent<AsyncPayload> byDefault
    ) {
        this.special = special;
        this.byDefault = byDefault;
    }

    public AsyncComponent<AsyncPayload> special() {
        return special;
    }

    public AsyncComponent<AsyncPayload> byDefault() {
        return byDefault;
    }
}
