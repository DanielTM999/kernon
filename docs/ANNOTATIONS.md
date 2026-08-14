# Referência de anotações

Esta referência descreve as combinações confirmadas no Kernon 1.2.0. “Depende de” indica
uma relação necessária para a anotação produzir o efeito esperado; a mera possibilidade
de o Java aceitar duas anotações no mesmo elemento não significa que Kernon combine seus
efeitos.

## Componentes e resolução

| Anotação | Alvo | Default/efeito | Depende de e combinações |
|---|---|---|---|
| `@Component` | classe ou método | classe gerenciada; em classe, prototype | método só é processado dentro de `@Configuration` |
| `@Service` | classe ou método | meta-anotada com `@Component`; mesma semântica de registro | não precisa coexistir com `@Component` |
| `@Singleton` | classe | reutiliza uma instância | só produz efeito útil em classe registrada como componente |
| `@Qualifier` | classe, método, campo ou parâmetro | nomeia/seleciona um registro; vazio vira `default` | em produtor padrão, use o atributo qualifier de `@Component`/`@Service` |
| `@Primary` | classe ou método | candidato para lookup `default` | uso previsível confirmado em classe componente; no máximo um por tipo indexado |
| `@Inject` | campo ou parâmetro | campo é injetado; `qualifier` default é `default` | construtor não precisa de `@Inject`; em parâmetro use `@Qualifier` |
| `@MainConstructor` | construtor | prefere esse construtor quando não há construtor vazio | marque no máximo um; construtor vazio ainda vence |
| `@Profile` | tipo, método ou meta-anotação | ativo se algum valor coincidir com um profile selecionado | em produtor, é avaliado antes da montagem do grafo; classe e método precisam estar ativos |
| `@ExcludeRootRegistration` | classe | não cria alias para a superclasse direta | aliases de interfaces continuam sendo registrados |
| `@DisableInjectionWarn` | classe/campo/parâmetro/método conforme uso | suprime logs de falha de injeção | não transforma dependência em obrigatória nem fornece fallback |

### Qualifiers previsíveis

Em classe, use `@Qualifier`:

```java
@Singleton
@Service
@Qualifier("postal")
public class PostalNotifier implements Notifier {}
```

Os atributos `@Component(qualifier = "...")` e `@Service(qualifier = "...")` existem, mas
o caminho de registro de **classe** não os lê. Eles são lidos em método produtor:

```java
@Configuration
public class NotifierConfig {
    @Service(qualifier = "postal")
    public Notifier postalNotifier() {
        return new PostalNotifier();
    }
}
```

Não use `@Qualifier` diretamente junto de `@Component`/`@Service` no produtor: o atributo
da anotação componente é consultado primeiro, inclusive quando vale `default`.

Em campo, ambas as formas funcionam; `@Qualifier` tem precedência:

```java
@Inject(qualifier = "postal")
private Notifier first;

@Inject
@Qualifier("postal")
private Notifier second;
```

Em parâmetro de construtor ou factory, use `@Qualifier`:

```java
public DeliveryService(@Qualifier("postal") Notifier notifier) {}
```

O atributo de `@Inject` em parâmetro não é considerado pelo resolvedor de construtor.

### Primary

```java
@Singleton
@Service
@Primary
public class MainNotifier implements Notifier {}
```

- lookup default consulta primary primeiro;
- lookup com qualifier explícito ignora primary;
- sem primary, apenas o registro literalmente `default` é usado;
- segundo primary para um mesmo tipo causa falha de registro;
- não combine `@Primary` com `@Qualifier` no mesmo bean, pois o qualifier pode impedir a
  indexação primary.
- embora `@Primary` aceite métodos, um produtor padrão também precisa de `@Component` ou
  `@Service`, cujo qualifier é resolvido antes. Use `@Primary` em classe componente para
  obter comportamento previsível.

## Configuração e factories

| Anotação | Alvo | Default/efeito | Depende de e combinações |
|---|---|---|---|
| `@Configuration` | classe | habilita descoberta de métodos produtores | métodos precisam de `@Component`/`@Service` |
| `@BeanDefinition` | método | `proxyType = STATIC`, isto é, singleton | não descobre o método sozinho; combine com `@Component`/`@Service` |
| `@Import` | classe/meta-anotação | adiciona classes à descoberta recursivamente | a classe importada ainda precisa de anotação reconhecida |
| `@BeforeInitialization` | classe | nenhum comportamento de runtime confirmado | não é consultada pelo código atual |

`@Configuration.order` e `@Configuration.lazy` possuem defaults `0` e `false`, mas não há
leitura desses atributos na implementação atual. Não os use para controlar ordem ou
lazy-loading.

Exemplo correto de factory prototype:

```java
@Configuration
public class RequestConfig {
    @Component
    @BeanDefinition(proxyType = BeanDefinition.ProxyType.INSTANCE)
    public RequestContext requestContext() {
        return new RequestContext();
    }
}
```

`@BeanDefinition` sozinho seria ignorado, pois a descoberta testa a presença de
`@Component`, direta ou como meta-anotação.

### Profile em produtores

`@Profile` pode selecionar uma configuração inteira ou apenas um produtor:

```java
@Configuration
public class ClientConfig {
    @Profile("dev")
    @Component
    public ApiClient apiClient() {
        return new DevApiClient();
    }
}
```

O método acima somente entra no grafo quando `dev` está ativo. Caso contrário, ele não é
invocado e `ApiClient` não é registrado por esse produtor. Se a classe também possuir
`@Profile`, as duas condições precisam ser satisfeitas. Dentro de uma mesma anotação,
basta a correspondência de um dos valores.

## Boot e ciclo de vida

| Anotação | Alvo | Default/efeito | Regras |
|---|---|---|---|
| `@ApplicationBoot` | classe principal | sem default para `value` | seleciona o bootable |
| `@ApplicationEntryPoint` | classe | prioriza a classe na descoberta pela pilha | útil quando não há `main` convencional |
| `@OnBoot` | método do bootable | ponto principal pós-container | um único método `static`, `void`, público/protegido |
| `@OnApplicationFail` | método do bootable | handler simples | um único método com `Throwable` e `Thread` opcional |
| `@LifecycleHook` | método do bootable | evento `AFTER_CONTAINER_LOAD`, `order = 0` | `static`, `void`, público/protegido; menor order primeiro |
| `@DependencyContainerFactory` | bootable | `DependencyContainerStorage.class` | tenta obter um container de uma factory estática |
| `@DisableAop` | tipo ou método | desabilita proxy conforme o caminho | no bootable desabilita AOP global do boot gerenciado |

Eventos de `@LifecycleHook`:

| Evento | Momento | Parâmetros |
|---|---|---|
| `BEFORE_ALL` | antes da BootThread e do `load()` | nenhum |
| `AFTER_CONTAINER_LOAD` | depois de `load()` | container, args ou beans |
| `AFTER_STARTUP_METHOD` | depois de `@OnBoot` e runners | container, args ou beans |
| `AFTER_ALL` | final da tentativa de boot | container, args ou beans |
| `ON_CLOSE` | início do shutdown hook | container, args ou beans |

Menor `order` executa primeiro. Empates não têm ordem garantida.

## Criação e destruição

| Anotação | Momento | Ordem | Falha |
|---|---|---|---|
| `@PostCreation` | após injeção e criação do proxy | menor `order` primeiro | logada e ignorada |
| `@PreDestroy` | `unload()` global ou descarga externa seletiva | maior `order` primeiro no mesmo objeto | logada e os demais continuam |

Use métodos de instância sem parâmetros. A implementação torna os métodos acessíveis, mas
não valida de forma completa a assinatura. Métodos `@PostCreation` com parâmetros não são
suportados pelo caminho atual.

No `unload()` global, cada singleton do container principal ou instância externa possuída
é destruído uma vez, mesmo quando aparece em vários aliases. Prototypes não são retidos
pelo container e, por isso, não recebem descarte automático. Na descarga externa seletiva,
a ordem entre componentes segue o inverso da criação; entre singletons normais
independentes, não há ordem total documentada. Em proxies AOP, métodos copiados ou
sobrescritos são deduplicados por assinatura antes da invocação.

## Assíncrono

| Uso | Depende de | Forma de resolução |
|---|---|---|
| `@Async` em método de negócio | `@EnableAsync`, AOP ativo e bean chamado pelo proxy | retorno async do método |
| `@Async` em classe | `@Component` ou `@Service` | `AsyncComponent<T>` |
| `@Async` em método produtor | `@Configuration` e `@Component`/`@Service` no método | registra `AsyncComponent<T>` sem bloquear a carga |
| `@EnableAsync` | classe descoberta, preferencialmente bootable | importa `AsyncAspect` via `@Import` |

`@DisableAop` impede a interceptação de método `@Async`. O `AsyncAspect` também exclui
métodos em classes `@Configuration`. A anotação de classe não precisa de `@EnableAsync`,
pois sua criação em background é responsabilidade do container, não do aspecto.

Retornos de método aceitos: `void`/`Void`, `Future`, `CompletableFuture`,
`CompletionStage` e `AsyncResult`. Qualquer outro retorno falha quando o método é
interceptado.

Em método produtor de `@Configuration`, `@Async` não usa o aspecto e não depende de
`@EnableAsync`. Ele é açúcar sintático para um registro semelhante a
`AsyncRegistrationFunction<T>`:

```java
@Configuration
public class ClientConfig {
    @Async
    @Component
    public ApiClient apiClient() {
        return new ApiClient();
    }

    @Component
    public ClientFacade facade(AsyncComponent<ApiClient> client) {
        return new ClientFacade(client);
    }
}
```

- `AsyncComponent<ApiClient>` é registrado imediatamente;
- a factory roda em `MainExecutor-Worker-*` sem bloquear a passagem de configuração;
- produtores posteriores podem executar enquanto a factory ainda está em andamento;
- `ApiClient` não é registrado diretamente; um produtor que o pedir como parâmetro falha
  e orienta o uso de `AsyncComponent<ApiClient>`;
- a falha da factory conclui `AsyncResult` excepcionalmente e aparece em `await()` ou nos
  callbacks; ela não falha retroativamente uma carga que já terminou;
- na carga externa, a tarefa é rastreada e pode ser cancelada no unload.

O método deve retornar diretamente o tipo `T`. Não combine `@Async` com retorno
`RegistrationFunction`/`AsyncRegistrationFunction` nem com
`@BeanDefinition(proxyType = INSTANCE)` ou `@Primary`. Qualifiers são suportados. Para
escolher executor, qualifier e supplier explicitamente, retorne
`AsyncRegistrationFunction<T>` de um produtor síncrono.

## Eventos

| Anotação | Alvo | Default/efeito | Dependência |
|---|---|---|---|
| `@EventListener` | método | `async = false`, `order = 0` | bean instanciado e registrado no publisher |
| `@Event` | classe | torna o bean elegível ao scan inicial principal | combine com componente que tenha listeners |
| `@Event` | parâmetro | identifica o evento em listener multiparâmetro | exatamente um quando há mais de um parâmetro |

No container principal, use `@Event` na classe e `@EventListener` no método. Em carga
externa, o registro procura métodos listeners diretamente e não exige `@Event` na classe.

O método listener precisa de pelo menos um parâmetro. Com um, ele é o evento; com vários,
os demais são DI. Visibilidade é ajustada por reflexão e retornos são ignorados.

## Scheduler

| Anotação | Alvo | Default/efeito | Depende de |
|---|---|---|---|
| `@EnableSchedule` | bootable | `threads = 2`, mínimo efetivo 2 | boot gerenciado |
| `@Schedule` | classe descoberta | marca classe com tarefas | `@EnableSchedule` |
| `@ScheduleMethod` | método sem parâmetros | ms, `time = 0`, delay 0, periódico | classe `@Schedule` |

O `time = 0` declarado vira 1000 em runtime. `periodic = true` usa taxa fixa. A classe
`@Schedule` não precisa ser componente, pois o scheduler pede `newInstance` ao container.

## AOP e tratamento de exceção

Anotações AOP como `@Aspect`, `@Pointcut`, `@BeforeExecution`, `@AfterExecution`,
`@AfterException` e `@OnMainMethod` dependem de AOP habilitado e de objetos criados pelo
container. `@DisableAop` em aspecto/classe/método altera os pontos onde proxy/interceptação
é aplicada; não misture instâncias construídas com `new` e instâncias gerenciadas esperando
o mesmo comportamento.

O boot possui três mecanismos diferentes:

- `@OnApplicationFail`: fallback estático definido antes do container;
- `@ExceptionHandler`: handler gerenciado selecionado após o load;
- `@ControllerAdvice`: handler adicional preparado em background.

Mantenha no máximo uma classe de cada tipo. A seleção entre múltiplas classes não possui
ordem determinística confirmada.

### Relações dos handlers

| Classe | Métodos internos | Assinatura recomendada | Seleção |
|---|---|---|---|
| `@ExceptionHandler` | `@OnException(Tipo.class)` | método público com `Throwable` e/ou `Thread` | tipo exato; depois, handler atribuível mais próximo |
| `@ControllerAdvice` | `@HandleException({Tipo.class})` | método com `Throwable` e/ou `Thread` | tipo exato; depois, primeiro compatível do mapa |

`@OnException` fora de `@ExceptionHandler` e `@HandleException` fora de
`@ControllerAdvice` não são escaneados. Os métodos não recebem DI; cada parâmetro precisa
ser atribuível a `Throwable` ou `Thread`. Para o handler gerenciado, use método público,
pois esse invocador não ajusta acessibilidade. O advice ajusta acessibilidade por reflexão.

Não combine `@ExceptionHandler` e `@ControllerAdvice` na mesma classe. O bootstrap os
descobre em fases diferentes e não existe contrato claro para essa composição. Para
seleção previsível, declare handlers de tipos disjuntos ou um handler exato e um fallback
amplo; múltiplos handlers compatíveis no advice não têm desempate por especificidade.

## Package scan

`@PackageScanIgnore` é repetível via `@PackageScansIgnore`, com:

- `ignorePackage = {}`;
- `scanType = INCREMENT`;
- `scanElement = "default"`, tratado como package; apenas `"jar"` seleciona jars.

No container padrão há uma limitação que impede os filtros aplicados pelo boot de
alcançarem a varredura efetiva. Consulte
[Controle de package scan](PackageScanIgnoreReadMe.md) antes de usar.
