package dtm.di.testsupport;

import dtm.di.annotations.BeanDefinition;
import dtm.di.annotations.Configuration;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Service;
import dtm.di.annotations.aop.DisableAop;
import dtm.di.settings.AppSettings;
import dtm.di.settings.JsonAppSettings;

@Configuration
@Profile("configuration-settings")
public class ExternalSettingsConfiguration {

    @BeanDefinition
    @Service
    @DisableAop
    public AppSettings appSettings() {
        return new JsonAppSettings("settings.configuration-bean.json");
    }
}
