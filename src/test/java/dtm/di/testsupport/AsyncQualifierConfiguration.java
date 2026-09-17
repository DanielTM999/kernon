package dtm.di.testsupport;

import dtm.di.annotations.Async;
import dtm.di.annotations.Component;
import dtm.di.annotations.Configuration;
import dtm.di.annotations.Profile;

@Configuration
@Profile("async-qualifier")
public class AsyncQualifierConfiguration {

    @Async
    @Component
    public AsyncPayload defaultPayload() {
        return new AsyncPayload("default-payload");
    }

    @Async
    @Component(qualifier = "special")
    public AsyncPayload specialPayload() {
        return new AsyncPayload("special-payload");
    }
}
