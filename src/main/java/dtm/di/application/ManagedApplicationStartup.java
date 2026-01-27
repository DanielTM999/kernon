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
        ManagedApplication.doRun(log, args);
    }

}
