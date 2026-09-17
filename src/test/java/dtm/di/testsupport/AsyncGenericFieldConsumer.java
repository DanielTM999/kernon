package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;
import dtm.di.prototypes.async.AsyncComponent;

@Singleton
@Component
@Profile("generics-async")
public class AsyncGenericFieldConsumer {

    @Inject
    private AsyncComponent<GenericProcessor<FooPayload>> foo;

    @Inject
    private AsyncComponent<GenericProcessor<BarPayload>> bar;

    @Inject
    private AsyncComponent<GenericProcessor<String>> fromAsyncClass;

    public AsyncComponent<GenericProcessor<FooPayload>> foo() {
        return foo;
    }

    public AsyncComponent<GenericProcessor<BarPayload>> bar() {
        return bar;
    }

    public AsyncComponent<GenericProcessor<String>> fromAsyncClass() {
        return fromAsyncClass;
    }
}
