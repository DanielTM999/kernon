package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Configuration;
import dtm.di.annotations.Profile;

@Configuration
@Profile("generics-producer")
public class GenericProducerConfiguration {

    @Component
    public GenericProcessor<FooPayload> producedFooProcessor() {
        return () -> "produced-foo";
    }

    @Component
    public GenericProcessor<BarPayload> producedBarProcessor() {
        return () -> "produced-bar";
    }
}
