package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics-producer")
public class GenericProducerConsumer {

    @Inject
    private GenericProcessor<FooPayload> fooProcessor;

    @Inject
    private GenericProcessor<BarPayload> barProcessor;

    public GenericProcessor<FooPayload> fooProcessor() {
        return fooProcessor;
    }

    public GenericProcessor<BarPayload> barProcessor() {
        return barProcessor;
    }
}
