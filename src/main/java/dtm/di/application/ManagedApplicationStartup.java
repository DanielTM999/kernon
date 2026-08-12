package dtm.di.application;

import dtm.di.application.startup.ManagedApplication;

public class ManagedApplicationStartup {

    public static void doRun(){
        doRun(false, new String[0]);
    }

    public static void doRun(boolean log){
        doRun(log, new String[0]);
    }

    public static void doRun(String[] args){
        doRun(false, args);
    }

    public static void doRun(boolean log, String[] args){
        doRun(log, args, null);
    }

    /**
     * Inicializa a aplicação usando a classe principal informada. Quando {@code mainClass}
     * for {@code null}, a classe principal será identificada automaticamente.
     *
     * @param log habilita os logs de inicialização
     * @param args argumentos de inicialização
     * @param mainClass classe principal opcional da aplicação
     */
    public static void doRun(boolean log, String[] args, Class<?> mainClass){
        ManagedApplication.doRun(log, args, mainClass);
    }

}
