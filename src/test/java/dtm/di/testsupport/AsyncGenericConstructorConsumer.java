package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;
import dtm.di.prototypes.async.AsyncComponent;

@Singleton
@Component
@Profile("generics-async")
public class AsyncGenericConstructorConsumer {

    private final AsyncComponent<GenericProcessor<FooPayload>> foo;
    private final AsyncComponent<GenericProcessor<BarPayload>> bar;

    public AsyncGenericConstructorConsumer(
            AsyncComponent<GenericProcessor<FooPayload>> foo,
            AsyncComponent<GenericProcessor<BarPayload>> bar
    ) {
        this.foo = foo;
        this.bar = bar;
    }

    public AsyncComponent<GenericProcessor<FooPayload>> foo() {
        return foo;
    }

    public AsyncComponent<GenericProcessor<BarPayload>> bar() {
        return bar;
    }
}
