package dtm.di.utils;


public interface Locker {
    void lock();
    void unlock();

    static Locker getNewLocker(){
        return new Locker() {
            private boolean released = false;

            @Override
            public synchronized void lock() {
                while (!released) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            @Override
            public synchronized void unlock() {
                this.released = true;
                this.notifyAll();
            }
        };
    }
}
