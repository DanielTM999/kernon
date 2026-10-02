package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Qualifier;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Qualifier("boletoPayment")
@Profile("beanmap")
public class CardPayment implements PaymentMethod {
    @Override
    public String name() {
        return "card";
    }
}
