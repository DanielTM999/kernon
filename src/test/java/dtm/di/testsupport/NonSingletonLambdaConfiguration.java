package dtm.di.testsupport;

import dtm.di.annotations.BeanDefinition;
import dtm.di.annotations.Component;
import dtm.di.annotations.Configuration;
import dtm.di.annotations.Profile;

@Configuration
@Profile("non-singleton-lambda")
public class NonSingletonLambdaConfiguration {

    @Component
    @BeanDefinition(proxyType = BeanDefinition.ProxyType.INSTANCE)
    public GenericProcessor<FooPayload> prototypeLambdaProcessor() {
        return () -> "prototype-lambda";
    }
}
