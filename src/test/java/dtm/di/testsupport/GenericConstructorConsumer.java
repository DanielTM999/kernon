package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics")
public class GenericConstructorConsumer {

    private final GenericProcessor<FooPayload> fooProcessor;
    private final GenericProcessor<BarPayload> barProcessor;

    public GenericConstructorConsumer(
            GenericProcessor<FooPayload> fooProcessor,
            GenericProcessor<BarPayload> barProcessor
    ) {
        this.fooProcessor = fooProcessor;
        this.barProcessor = barProcessor;
    }

    public GenericProcessor<FooPayload> fooProcessor() {
        return fooProcessor;
    }

    public GenericProcessor<BarPayload> barProcessor() {
        return barProcessor;
    }
}
