# Kernon - Guia de Uso

## Visão Geral
O `Kernon` é um sistema de gerenciadmento podendo ser auto gerenciado para aplicações Java que oferece:
- Injeção de dependência automática
- Hooks de ciclo de vida
- Configuração simplificada
- Suporte a AOP (Aspect-Oriented Programming)
- Conjunto de classes que integram Injetor de dependecias com suporte a AOP e um boot de aplicação simplificado


## Visão Geral Arquitetural

O `ManagedApplicationStartup` é um sistema modular que combina três componentes essenciais:

### 1. `ApplicationBoot` - Inicializador Gerenciado
- **Função Principal**: Orquestração do ciclo de vida da aplicação
- **Responsabilidades**:
    - Sequenciamento de inicialização
    - Gestão de hooks (@LifecycleHook)
    - Disparo de eventos de ciclo de vida


### 2. `DependencyContainer` - Injetor Independente
- **Função Principal**:
    - Pode ser usado em conjunto com `ApplicationBoot` ou isoladamente
    - Acesso direto em qualquer fase


### 3. `AOP Integrado` - Opcional
- **Caracteristica**:
    - Acoplamento com DI
    - Desativado por padrão no container
    - Ativado por padrão pelo `ApplicationBoot`
    - Desligável via @DisableAop no `ApplicationBoot`
    - Desligável via função disableAOP() pelo comteiner

### 4. `Agendador de Tarefas` - (@Schedule) opicional
- **Função Principal**: 
  - Permite a execução automática de métodos em intervalos de tempo definidos
- **Caracteristica**:
  - Execução assíncrona e independente do restante do ciclo de vida da aplicação
  - Desativado por padrão — deve ser explicitamente habilitado com @EnableSchedule
  - Utiliza Pool de Threads interno para controle de concorrência
  - Pode ser ativado com configuração de número de threads personalizável


![Diagrama de Arquitetura](/docs/imgs/arquitetura_managed_app.png)


# Configuração Básica

## AutoGerenciado

### 1. Classe Principal
Anote sua classe principal(a com o main(String[] args)) com `@ApplicationBoot`:

## Sintaxe Básica

```java
import dtm.di.startup.ManagedApplicationStartup;
import dtm.di.annotations.boot.ApplicationBoot;

@ApplicationBoot
public class MyApp {
    public static void main(String[] args) {
        boolean log = true;
        ManagedApplicationStartup.doRun(log); // true para habilitar logs
    }
}
```

### Uso de Classe Bootable Externa com @ApplicationBoot


### Classe Principal com Boot externo
O `@ApplicationBoot` permite especificar uma classe externa para centralizar toda a configuração de inicialização da aplicação, separando-a da classe principal.

## Sintaxe Básica

```java
import dtm.di.startup.ManagedApplicationStartup;
import dtm.di.annotations.boot.ApplicationBoot;

@ApplicationBoot(AppBootConfig.class)  // Classe de configuração externa
public class MainApplication {
    public static void main(String[] args) {
        ManagedApplicationStartup.doRun(true);
    }
}
```

## Benefícios da Classe Bootable Externa

### Separação de Conceitos
- **Classe principal limpa**: Remove a complexidade de configuração da classe com método `main`
- **Centralização**: Toda configuração fica em um local dedicado

### Reusabilidade
- **Compartilhamento entre projetos**: mutiplas inicializações

```java
import dtm.di.annotations.boot.ApplicationBoot;

// Projeto A
@ApplicationBoot(SharedBootConfig.class)

// Projeto B
@ApplicationBoot(SharedBootConfig.class)

//Apenas uma
public class Main{}
```

# Anotações de Ciclo de Vida: `@OnBoot` e `@LifecycleHook`

##  `@OnBoot` - Ponto Principal de Inicialização

### Características:
- **Único por classe** (se declarado múltiplos, apenas 1 executa)
- **Ordem**: Executado após o carregamento do container DI
- **Requisitos**:
```java
  @OnBoot
  public static void método() {  
      // Deve ser:
      // static
      // public 
      // void
      // sem parâmetros
  }
```

##  `@LifecycleHook` - Controle Fino do Ciclo de Vida

### Eventos Disponíveis e Seus Momentos Exatos

| Evento                  | Fase de Execução                | Contexto Disponível                          | Uso Típico                          |
|-------------------------|---------------------------------|---------------------------------------------|-------------------------------------|
| `BEFORE_ALL`            | **Primeira etapa** do boot      | Container DI ainda não inicializado         | - Configurar variáveis de ambiente<br>- Validar requisitos do sistema |
| `AFTER_CONTAINER_LOAD`  | Após **DI completo** mas antes do `@OnBoot` | Container DI pronto, mas app não iniciado | - Validar dependências injetadas<br>- Configurar proxies dinâmicos |
| `AFTER_STARTUP_METHOD`  | Imediatamente após `@OnBoot`    | App inicializado mas antes de serviços rodando | - Health checks iniciais<br>- Registrar métricas |
| `AFTER_ALL`             | **Última etapa** do processo    | Todos serviços ativos                       | - Liberar recursos temporários<br>- Registrar fim do boot |

### Exemplo Prático com Todos Eventos

```java
import dtm.di.annotations.boot.ApplicationBoot;
import dtm.di.annotations.boot.LifecycleHook;

public class AppLifecycle {

    @LifecycleHook(LifecycleHook.Event.BEFORE_ALL)
    public static void prepareEnvironment() {
        log("1. Configurando ambiente...");
    }

    @LifecycleHook(LifecycleHook.Event.AFTER_CONTAINER_LOAD)
    public static void postDICheck() {
        log("2. Verificando dependências...");
    }

    // @OnBoot executaria aqui

    @LifecycleHook(LifecycleHook.Event.AFTER_STARTUP_METHOD)
    public static void postStartup() {
        log("3. Pós-inicialização...");
    }

    @LifecycleHook(LifecycleHook.Event.AFTER_ALL)
    public static void cleanup() {
        log("4. Finalizando processo...");
    }
}
```

## ⚙ Fluxo de Execução Garantido

### Ordem Estrita de Processamento
1. `BEFORE_ALL`  
   → 2. `AFTER_CONTAINER_LOAD`  
   → 3. `@OnBoot`  
   → 4. `AFTER_STARTUP_METHOD`  
   → 5. `AFTER_ALL`

### Regras Críticas

- **Idempotência**:
  ```diff
  + Métodos devem produzir o mesmo resultado em múltiplas execuções.
  - Evite operações não determinísticas.
  ```
- **Performance**: Cada hook adiciona tempo de inicialização → Mantenha operações essenciais

# Agendador de Tarefas (@Schedule)

- O sistema de agendamento permite que classes e métodos sejam executados periodicamente de forma automática após o boot da aplicação.

## Habilitando o agendador

- Para utilizar o sistema de tarefas agendadas, adicione a anotação **`@EnableSchedule`** na classe principal ou na classe marcada no **`@ApplicationBoot`**

```java
    import dtm.di.annotations.*;
    import dtm.di.startup.ManagedApplicationStartup;    

    @EnableSchedule(threads = 4)
    @ApplicationBoot
    public class AppMain {
        public static void main(String[] args) {
            ManagedApplicationStartup.doRun(true);
        }
    }
```

- **threads**: número de threads disponíveis para executar os métodos agendados. O padrão mínimo é 2.

## Classe com tarefas agendadas

- Para uma classe participar do agendamento, ela deve ser marcada com **`@Schedule`**.

```java
    import dtm.di.annotations.*;

    @Schedule
    public class RelatorioScheduler {
    
        @ScheduleMethod(time = 10, timeUnit = TimeUnit.SECONDS, startDelay = 5, periodic = true)
        public void gerarRelatorioPeriodicamente() {
            System.out.println("Relatório gerado: " + LocalDateTime.now());
        }
    }
    
```

### ⚙ Detalhes de `@ScheduleMethod`

| Parâmetro    | Descrição                                                                 |
|--------------|---------------------------------------------------------------------------|
| `time`       | Intervalo entre execuções (em conjunto com `timeUnit`)                    |
| `timeUnit`   | Unidade de tempo (`TimeUnit.SECONDS`, `TimeUnit.MILLISECONDS`, etc.)      |
| `startDelay` | Tempo de atraso inicial antes da primeira execução                        |
| `periodic`   | `true` para execução periódica, `false` para execução única (delayed)     |

> **Importante**: o método deve ser `public`, sem parâmetros e não pode lançar exceções verificadas.

---

###  Comportamento de Execução

- O framework utiliza um `ScheduledThreadPoolExecutor` interno.
- Métodos agendados são executados **assíncronamente** após a finalização do método `@OnBoot`.
- Se `@EnableSchedule` não estiver presente, nenhuma tarefa será executada, mesmo com `@Schedule`.


# Diagrama de Fluxo
![Diagrama de Fluxo](/docs/imgs/fluxo_managed_app.png)


## Não Gerenciado

### 1. Classe Principal Exemplo

```java
import dtm.di.annotations.*;
import dtm.di.annotations.aop.Aspect;
import dtm.di.annotations.aop.BeforeExecution;
import dtm.di.annotations.aop.Pointcut;
import dtm.di.storage.DependencyContainerStorage;
import dtm.di.core.DependencyContainer;

public class Main {

    private final MainService1 mainService1;

    @Inject
    @Qualifier("MainService2")
    private MainService2 mainService2;

    @Inject(qualifier = "MainService3")
    private MainService3 mainService3;

    public Main(MainService1 mainService) {
        this.mainService = mainService;
    }

    public static void main(String[] args) {
        DependencyContainer dependencyContainer = DependencyContainerStorage.getInstance(Main.clas);
        dependencyContainer.enableParallelInjection();
        dependencyContainer.enableAOP();
        dependencyContainer.load();

        Main mainClass1 = dependencyContainer.newInstance(Main.class);

        Main mainClass2 = new Main(new MainService1());
        dependencyContainer.injectDependencies(mainClass2);
    }

    @Singleton
    @Service
    public static class MainService1 {

        public void action() {
            System.out.println("MainService1.action");
        }
    }

    @Qualifier("MainService2")
    @Service
    public static class MainService2 {

        public void action() {
            System.out.println("MainService2.action");
        }
    }

    @Service
    public static class MainService3 {

        public void action() {
            System.out.println("MainService3.action");
        }
    }

    @Aspect
    public static class MainAspect {

        @Pointcut
        public boolean pointcut(Method method, Object[] args, @ProxyInstance Object proxy) {
            return true;
        }

        @BeforeExecution
        public void before(Method method, Object[] args, @ProxyInstance Object proxy) {

        }

        @BeforeExecution
        public Object after(Method method, Object[] args, @ProxyInstance Object proxy, @ResultProxy Object currentResult) {
            return currentResult;
        }

    }

    @Configuration
    public static class MinhaConfig {

        // BeenDefinition.ProxyType.INSTANCE para não simgleton mas cuidado pois o não singleton ele recontroi a intancia
        @BeenDefinition(proxyType = BeenDefinition.ProxyType.STATIC)
        @Service(qualifier = "MainService3")
        public MainService3 mainService3() {
            return new MainService3();
        }
    }

}
```

