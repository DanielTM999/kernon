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
import dtm.di.application.startup.ManagedApplication;
import dtm.di.annotations.boot.ApplicationBoot;

@ApplicationBoot
public class MyApp {
  public static void main(String[] args) {
    boolean log = true;
    ManagedApplication.doRun(log); // true para habilitar logs
  }
}
```

### Uso de Classe Bootable Externa com @ApplicationBoot


### Classe Principal com Boot externo
O `@ApplicationBoot` permite especificar uma classe externa para centralizar toda a configuração de inicialização da aplicação, separando-a da classe principal.

## Sintaxe Básica

```java
import dtm.di.application.startup.ManagedApplication;
import dtm.di.annotations.boot.ApplicationBoot;

@ApplicationBoot(AppBootConfig.class)  // Classe de configuração externa
public class MainApplication {
  public static void main(String[] args) {
    ManagedApplication.doRun(true);
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

import dtm.di.application.startup.ManagedApplication;

@EnableSchedule(threads = 4)
@ApplicationBoot
public class AppMain {
  public static void main(String[] args) {
    ManagedApplication.doRun(true);
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


# Resolução de Dependências Ambíguas (`@Primary`)

Quando há **mais de uma implementação** do mesmo tipo no container e nenhum
`@Qualifier` é especificado na injeção, o bean anotado com `@Primary` é o
escolhido. Sem `@Primary`, o lookup mantém a regra atual (qualifier `"default"`).

```java
import dtm.di.annotations.*;

public interface PaymentGateway {
    void charge(long cents);
}

@Service
@Primary
public class StripeGateway implements PaymentGateway {
    public void charge(long cents) { /* ... */ }
}

@Service
@Qualifier("paypal")
public class PaypalGateway implements PaymentGateway {
    public void charge(long cents) { /* ... */ }
}

@Service
public class CheckoutService {

    @Inject
    private PaymentGateway gateway; // resolve para StripeGateway (@Primary)

    @Inject
    @Qualifier("paypal")
    private PaymentGateway paypalGateway; // resolve para PaypalGateway
}
```

### Onde pode ser usado
- Em classes anotadas com `@Component`/`@Service`.
- Em métodos de fábrica dentro de `@Configuration` (junto com `@BeanDefinition`).

### Regras
- Se **nenhum** bean tiver `@Primary` e houver ambiguidade, a resolução continua
  selecionando o entry registrado sob qualifier `"default"` — comportamento
  retrocompatível.
- Se mais de um bean estiver marcado com `@Primary`, vence o **primeiro encontrado**
  na iteração do mapa. Considere isso um erro de design e prefira `@Qualifier` para
  desambiguar explicitamente.


# `@PreDestroy` — Liberação de Recursos

Simétrico a `@PostCreation`. Métodos anotados são executados antes do bean ser
descartado pelo container — durante `unload()` ou via shutdown hook do processo.

```java
import dtm.di.annotations.*;

@Service
public class ConnectionPool {

    private DataSource dataSource;

    @PostCreation
    public void open() {
        this.dataSource = createDataSource();
    }

    @PreDestroy(order = 10) // maior order roda primeiro
    public void close() {
        if (dataSource != null) dataSource.close();
    }
}
```

### Características
- O método deve ser **público**, **sem parâmetros**, retorno **`void`** e **não estático**.
- Maior `order` executa primeiro (ordem reversa em relação a `@PostCreation` —
  reflete a semântica natural de teardown: feche o que foi aberto por último).
- Exceções são logadas e ignoradas — o shutdown nunca para pela metade.
- Disparado automaticamente pelo shutdown hook do `ManagedApplication`.
  Em uso não-gerenciado, chame `dependencyContainer.unload()` no fim do programa.

> **Limitação inerente da JVM**: `kill -9` ou crash não dispara shutdown hook.
> Não use `@PreDestroy` para garantias de durabilidade — apenas para liberação ordenada.


# `ApplicationRunner` — Tarefas Pós-Boot

Beans que implementam `ApplicationRunner` são executados após o `@OnBoot`,
recebendo os argumentos da linha de comando. Útil para migrações, importação
de dados, jobs únicos de inicialização — sem precisar transformar tudo em
método estático no bootable.

```java
import dtm.di.annotations.*;
import dtm.di.application.ApplicationRunner;

@Service
public class DataSeederRunner implements ApplicationRunner {

    @Inject
    private UserRepository users;

    @Override
    public void run(String[] args) throws Exception {
        if (args.length > 0 && "--seed".equals(args[0])) {
            users.save(new User("admin"));
        }
    }
}
```

### Comportamento
- Executado **após** o método `@OnBoot` e **antes** do hook `AFTER_STARTUP_METHOD`.
- Múltiplos runners executam na ordem em que o container os entrega.
- Exceções propagam para o handler de erro de boot (mesmo caminho de `@OnApplicationFail`).


# Eventos (`EventPublisher` + `@EventListener`)

Sistema de pub/sub para comunicação entre beans em tempo de execução —
complementar aos `@LifecycleHook` (que são apenas eventos do ciclo de boot).

### Publicar

Injete o `EventPublisher` em qualquer bean:

```java
import dtm.di.annotations.*;
import dtm.di.event.EventPublisher;

public record OrderPlacedEvent(String orderId, long amountCents) {}

@Service
public class OrderService {

    @Inject
    private EventPublisher events;

    public void place(Order order) {
        // ...persistir...
        events.publish(new OrderPlacedEvent(order.id(), order.total()));
    }
}
```

### Reagir

Métodos anotados com `@EventListener` em qualquer bean do container recebem
eventos compatíveis com o tipo do parâmetro (suporta herança e interfaces):

```java
import dtm.di.annotations.*;
import dtm.di.annotations.event.EventListener;

@Service
public class EmailNotifier {

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        // notifica cliente — síncrono na thread do publisher
    }

    @EventListener(async = true, order = 10)
    public void auditAsync(OrderPlacedEvent event) {
        // roda no executor do container, não bloqueia o publisher
    }
}
```

### Listeners com múltiplos parâmetros

Quando o método tem **mais de um parâmetro**, marque o receptor do evento com
`@Event`. Os demais parâmetros são resolvidos pelo container — exatamente como
em construtores de beans, inclusive com suporte a `@Qualifier`:

```java
import dtm.di.annotations.*;
import dtm.di.annotations.event.Event;
import dtm.di.annotations.event.EventListener;

@Service
public class OrderListeners {

    @EventListener
    public void onPlaced(@Event OrderPlacedEvent event, EmailService email, AuditLogger audit) {
        // 'event' vem do publisher; 'email' e 'audit' vêm do container
        email.send(event.orderId());
        audit.record(event);
    }

    @EventListener(async = true)
    public void onPlacedQualified(@Event OrderPlacedEvent event, @Qualifier("primary") MetricsCollector metrics) {
        metrics.increment("orders.placed");
    }
}
```

> Para listeners com **um único parâmetro**, `@Event` é opcional — o parâmetro é
> automaticamente tratado como receptor do evento. Se houver múltiplos parâmetros e
> nenhum (ou mais de um) estiver marcado com `@Event`, o listener é descartado com
> warning no log.

### Características
| Atributo                 | Comportamento                                                            |
|--------------------------|---------------------------------------------------------------------------|
| Match de tipo            | `parametro.isAssignableFrom(evento.getClass())` — herança e interfaces    |
| `async = false` (padrão) | Executa síncrono na thread chamadora; exceções propagam ao publisher      |
| `async = true`           | Executa no executor compartilhado do container; exceções são logadas      |
| `order`                  | Menor `order` executa antes (apenas em modo síncrono)                     |
| Receptor do evento       | Único parâmetro (auto) ou parâmetro marcado com `@Event` (multi-param)    |
| Demais parâmetros        | Resolvidos via container — suportam `@Qualifier`/`@Inject(qualifier=…)`   |
| Requisitos do método     | Não estático, retorno `void`                                              |

### Sobrescrever o publisher

O `EventPublisher` padrão é registrado automaticamente no fim do `load()`. Se quiser
substituí-lo (ex.: integrar com um message broker), basta registrar o seu via
`@Configuration` antes — o framework não sobrescreve um `EventPublisher` já presente.

```java
@Configuration
public class EventConfig {

    @BeanDefinition(proxyType = BeanDefinition.ProxyType.STATIC)
    @Service
    public EventPublisher customPublisher() {
        return new KafkaBackedPublisher(/* ... */);
    }
}
```


# Settings (`AppSettings` + `@Value`)

Camada de configuração externa baseada em um arquivo JSON — análogo ao
`IConfiguration` do C# e ao `@Value` do Spring. Funciona de duas formas:
**programática** (injetando a interface `AppSettings`) ou **declarativa**
(anotação `@Value` em campos).

## O arquivo `settings.json`

O framework lê **somente do classpath**: coloque o arquivo em
`src/main/resources/settings.json`. Ele será empacotado no JAR e carregado em
memória durante o boot. **É read-only em runtime** — para mudar valores, edite
o recurso e re-empacote.

Se o arquivo não existir no classpath, o framework loga um warning e segue
com configuração vazia — toda leitura passa a retornar o `defaultValue` fornecido.

```json
{
  "text": {
    "val": 1
  },
  "db": {
    "url": "jdbc:postgresql://localhost/app",
    "maxConnections": 20
  }
}
```



> Chaves em **notação de ponto** navegam pela árvore: `text.val`, `db.url`.

## Modo programático: injetar `AppSettings`

```java
import dtm.di.annotations.*;
import dtm.di.settings.AppSettings;

@Service
public class FeatureService {

    @Inject
    private AppSettings settings;

    public void run() {
        int v        = settings.getInt("text.val", 0);
        String url   = settings.getString("db.url", "jdbc:default");
        DbConfig db  = settings.getObject("db", DbConfig.class);
        boolean has  = settings.has("text.val");
    }
}
```

| Método                                    | Comportamento                                                                |
|-------------------------------------------|------------------------------------------------------------------------------|
| `getString/getInt/getLong/getDouble/getBoolean(key, default)` | Retorna o `default` se a chave não existir ou for inválida.                  |
| `getObject(key, type)`                    | Desserializa via Jackson; se ausente/erro, retorna nova instância via construtor default (ou `null` se a classe não tiver um). |
| `has(key)`                                | `true` se a chave existir (mesmo que com valor `null`).                      |

## Modo declarativo: `@Value`

```java
import dtm.di.annotations.*;
import dtm.di.annotations.settings.Value;

@Service
public class DbService {

    @Value(key = "db.url", defaultValue = "jdbc:h2:mem:default")
    private String url;

    @Value(key = "db.maxConnections", defaultValue = "10")
    private int maxConnections;

    @Value(key = "db")               // objeto inteiro
    private DbConfig config;
}
```

### Regras
- `@Value` **não exige** combinar com `@Inject` — sua presença basta para o
  framework resolver o campo.
- Para tipos primitivos / `String` / wrappers (`Integer`, `Long`, `Double`,
  `Boolean`, `Float`, `Short`, `Byte`), o `defaultValue()` (string) é
  parseado para o tipo do campo se a chave não existir.
- Para qualquer outro tipo: desserializado via Jackson. Se ausente ou inválido,
  o framework cria uma nova instância via construtor default. Sem construtor
  default → injetado `null`.

## Sobrescrever a implementação

O framework registra `JsonAppSettings` automaticamente durante `load()`,
apenas se nenhum `AppSettings` já estiver presente. Para usar outro nome de
recurso, ou outra fonte (variáveis de ambiente, Vault, banco de dados),
registre o seu via `@Configuration`:

```java
@Configuration
public class SettingsConfig {

    @Service
    @BeanDefinition(proxyType = BeanDefinition.ProxyType.STATIC)
    public AppSettings settings() {
        return new JsonAppSettings("config/myapp.json"); // outro recurso do classpath
    }
}
```


# Configurações 
- O framework utiliza a anotação `@Configuration` para configrar os gerenciamentos
- A resolução de dependências (em métodos, campos ou construtores) segue a seguinte ordem:
  - **Beans** externos registrados manualmente no container têm prioridade. Se uma dependência requisitada já estiver registrada externamente, ela será utilizada diretamente
  - **Beans internos detectados automaticamente**: caso a dependência não esteja registrada externamente, o framework tentará resolver e instanciar automaticamente um bean compatível, utilizando anotações como, @Component, etc.


# Diagrama de Fluxo
![Diagrama de Fluxo](/docs/imgs/fluxo_managed_app.png)


## Não Gerenciado

### 1. Classe Principal Exemplo

```java
import dtm.di.annotations.*;
import dtm.di.annotations.aop.Aspect;
import dtm.di.annotations.aop.BeforeExecution;
import dtm.di.annotations.aop.Pointcut;
import dtm.di.storage.containers.DependencyContainerStorage;
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

    // BeanDefinition.ProxyType.INSTANCE para não simgleton mas cuidado pois o não singleton ele recontroi a intancia
    @BeanDefinition(proxyType = BeanDefinition.ProxyType.STATIC)
    @Service(qualifier = "MainService3")
    public MainService3 mainService3() {
      return new MainService3();
    }
  }

}
```

