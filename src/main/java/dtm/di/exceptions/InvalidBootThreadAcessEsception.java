package dtm.di.exceptions;

import java.util.Locale;

public class InvalidBootThreadAcessEsception extends RuntimeException {

    public InvalidBootThreadAcessEsception() {
        super(determineMessage());
    }

    private static String determineMessage() {
        Locale currentLocale = Locale.getDefault();

        if (currentLocale.getLanguage().equals("pt")) {
            return "Tentativa inválida de acesso à BootThread: O acesso foi negado porque a aplicação " +
                    "ainda não foi inicializada ou não foi utilizada a rotina padrão " +
                    "'ManagedApplicationStartup.doRun' para o bootstrap.";
        }

        // Default: Inglês
        return "Invalid BootThread access attempt: Access denied because the application " +
                "has not been initialized or was started without using 'ManagedApplicationStartup.doRun'.";
    }

}
