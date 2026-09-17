package dtm.di.testsupport;

import dtm.di.annotations.BeanDefinition;
import dtm.di.annotations.Component;
import dtm.di.annotations.Configuration;
import dtm.di.annotations.Profile;

@Configuration
@Profile("non-singleton-generic")
public class NonSingletonGenericConfiguration {

    @Component
    @BeanDefinition(proxyType = BeanDefinition.ProxyType.INSTANCE)
    public GenericProcessor<FooPayload> prototypeFooProcessor() {
        return new OpenProcessor<>();
    }

    public static class OpenProcessor<T> implements GenericProcessor<T> {
        @Override
        public String describe() {
            return "prototype-foo";
        }
    }
}
