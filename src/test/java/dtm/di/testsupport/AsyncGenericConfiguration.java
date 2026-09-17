package dtm.di.testsupport;

import dtm.di.annotations.Async;
import dtm.di.annotations.Component;
import dtm.di.annotations.Configuration;
import dtm.di.annotations.Profile;
import dtm.di.prototypes.async.AsyncComponent;

@Configuration
@Profile("generics-async")
public class AsyncGenericConfiguration {

    @Async
    @Component
    public GenericProcessor<FooPayload> asyncFooProcessor() {
        return () -> "async-foo";
    }

    @Async
    @Component
    public GenericProcessor<BarPayload> asyncBarProcessor() {
        return () -> "async-bar";
    }

    @Component
    public AsyncGenericProducerConsumer producerConsumer(
            AsyncComponent<GenericProcessor<BarPayload>> bar
    ) {
        return new AsyncGenericProducerConsumer(bar);
    }
}
