package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("beanmap")
public class BoletoPayment implements PaymentMethod {
    @Override
    public String name() {
        return "boleto";
    }
}
