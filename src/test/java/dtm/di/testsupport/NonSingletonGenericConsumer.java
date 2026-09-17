package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("non-singleton-generic")
public class NonSingletonGenericConsumer {

    @Inject
    private GenericProcessor<FooPayload> processor;

    public GenericProcessor<FooPayload> processor() {
        return processor;
    }
}
