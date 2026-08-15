# Comportamento do framework

Esta é a referência canônica do comportamento operacional do Kernon 1.2.0. O conteúdo
foi conferido no código e, quando disponível, nos testes do repositório. Onde a ordem não
é garantida pelas estruturas usadas, o documento não cria uma garantia artificial.

## Modelo mental

Kernon possui duas camadas que podem ser usadas juntas ou separadamente:

- `ManagedApplication`: localiza uma classe de boot, configura o container padrão e
  orquestra hooks, `@OnBoot`, runners, scheduler e shutdown.
- `DependencyContainer`: descobre, registra, cria e injeta beans. No container padrão,
  a implementação é `DependencyContainerStorage`.

O container trabalha com uma chave lógica `(tipo indexado, qualifier)`. Um bean concreto
também pode ser indexado pela superclasse direta e pelas interfaces diretamente
implementadas. O qualifier default é `"default"`.

## Boot gerenciado

### Seleção das classes

Sem o overload que recebe `mainClass`, `ManagedApplication` inspeciona a pilha atual:

1. procura uma classe da pilha anotada com `@ApplicationEntryPoint`;
2. se não encontrar, procura uma classe da pilha com
   `public static void main(String[] args)`;
3. se não encontrar nenhuma, lança `InvalidBootException` antes de criar a thread de boot.

Depois de achar a classe principal:

- se ela tem `@ApplicationBoot(X.class)`, `X` é o bootable;
- sem `@ApplicationBoot`, a própria classe principal é o bootable.

`ApplicationBoot.value()` não possui default. Portanto, `@ApplicationBoot` sem argumento
não compila.

### O que é lido no bootable

O bootstrap inspeciona diretamente o bootable para localizar:

- `@OnBoot` e `@OnApplicationFail` em métodos declarados;
- `@LifecycleHook` em métodos declarados;
- `@EnableSchedule`;
- `@DisableAop`;
- `@DependencyContainerFactory`;
- `@PackageScanIgnore`.

Para reduzir dependência de detalhes do package scan, coloque essas opções no bootable,
não em uma classe arbitrária.

### Assinaturas do boot

`@OnBoot` precisa ser `static`, retornar `void` e ser `public` ou `protected`. Parâmetros
são permitidos e resolvidos na seguinte ordem por posição:

1. `DependencyContainer` exato recebe o container atual;
2. `String[]` exato recebe os argumentos de lançamento;
3. qualquer outro tipo usa `container.getDependency(tipo)`.

O código não rejeita antecipadamente uma dependência ausente: o parâmetro pode receber
`null`. Essa resolução não lê anotações de qualifier do parâmetro: usa o lookup default,
que pode selecionar um `@Primary`. Declare apenas um `@OnBoot`, pois a reflexão não oferece ordem estável quando há
mais de um e o último método válido encontrado é usado.

`@OnApplicationFail` precisa ser `static`, retornar `void`, ser `public` ou `protected` e
ter uma destas assinaturas recomendadas:

```java
public static void fail(Throwable error)
public static void fail(Throwable error, Thread thread)
```

Use `Throwable` exatamente. Embora a validação aceite alguns subtipos, um subtipo mais
estreito pode não aceitar todo erro encaminhado em runtime. Também deve existir no máximo
um handler desse tipo.

### Ordem e threads

| Ordem | Fase | Thread | Espera terminar? |
|---|---|---|---|
| 1 | preparação, descoberta do bootable e validação de `@OnBoot` | chamadora | sim |
| 2 | `LifecycleHook.Event.BEFORE_ALL` | chamadora | sim |
| 3 | `DependencyContainer.load()` | `BootThread` não daemon | sim, pela própria BootThread |
| 4 | preparação assíncrona de `ControllerAdvice` | `ControllerAdviceScannerThread` | não nesse ponto |
| 5 | `AFTER_CONTAINER_LOAD` | `BootThread` | sim |
| 6 | disparo do registro de schedules | `ForkJoinPool.commonPool()` | não |
| 7 | `@OnBoot` | `BootThread` | sim |
| 8 | `ApplicationRunner.run(args)` | `BootThread` | sim, um por vez |
| 9 | `AFTER_STARTUP_METHOD` | `BootThread` | sim |
| 10 | `AFTER_ALL` | `BootThread`, em `finally` | sim |

Consequências práticas:

- `doRun(...)` retorna assim que inicia a `BootThread`; não é uma barreira de readiness.
- Somente `BEFORE_ALL` termina antes de `doRun(...)` retornar.
- O registro das tarefas agendadas pode ocorrer antes, durante ou depois do corpo de
  `@OnBoot`, porque o boot apenas dispara esse trabalho.
- Um schedule com delay zero pode executar enquanto `@OnBoot` ou runners ainda executam.
- Runners são síncronos entre si, mas a ordem entre múltiplos runners não é garantida: o
  container os entrega por um mapa concorrente sem ordenação contratual.

### Hooks

Todos os hooks devem ser `static`, retornar `void` e ser `public` ou `protected`.

- `BEFORE_ALL` não aceita parâmetros.
- Os demais eventos resolvem `DependencyContainer`, `String[]` e beans da mesma forma que
  `@OnBoot`.
- Menor `order` executa primeiro dentro do mesmo evento.
- Empates de `order` não têm ordem garantida.
- `ON_CLOSE` executa no shutdown hook, antes do scheduler e do container serem encerrados.
- Uma exceção do hook vira `InvalidBootException`.

`AFTER_ALL` é chamado em `finally` mesmo quando o carregamento, `@OnBoot`, runner ou hook
anterior falha. Isso não significa que todos os recursos estejam ativos; trate esse evento
como finalização da tentativa de boot, não como sinal incondicional de sucesso.

## Fases do container

O `load()` do container padrão é síncrono para quem o chama e retorna cedo se o container
já estiver marcado como carregado. Sua sequência é:

1. carregar diretórios de plugin previamente adicionados;
2. descobrir classes pelo classpath;
3. expandir `@Import` recursivamente;
4. filtrar componentes, aspectos e profiles e construir o grafo de dependências;
5. separar métodos produtores que rodam antes ou depois dos serviços;
6. registrar o próprio container;
7. marcar o container como carregado;
8. executar métodos produtores que não dependem de serviços;
9. registrar `AppSettings` padrão se ausente;
10. aplicar a estratégia declarativa de injeção, se não houver configuração programática;
11. registrar `EventPublisher` padrão se ausente;
12. criar/registrar componentes por camadas do grafo;
13. executar métodos produtores que dependem de serviços;
14. escanear listeners de eventos elegíveis.

O estado `loaded` é definido antes das etapas 8 a 14. Se uma dessas etapas falhar, a carga
inicial é encapsulada em `UnloadError`, mas rollback total e restauração de `loaded` **não
são garantidos** pelo caminho inicial.

### Paralelismo de criação

O container organiza componentes em camadas de dependência. Camadas são processadas em
ordem; os componentes da mesma camada são criados em paralelo no executor principal.

- Uma dependência do grafo fica em camada anterior ao consumidor.
- Não existe ordem garantida entre componentes independentes da mesma camada.
- O executor principal possui `max(6, availableProcessors)` threads daemon.
- Classes `@Async` iniciam sua construção nesse executor, mas o container registra um
  `AsyncComponent<T>` sem aguardar a conclusão do objeto real.

O grafo considera campos `@Inject` e parâmetros de construtores. Dependências escondidas
em código de factory, estado global ou chamadas manuais não participam da ordenação.

### Profiles

A lista de profiles ativos segue esta precedência:

1. profiles passados explicitamente ao factory do container; no boot gerenciado, os
   argumentos CLI reconhecidos entram aqui;
2. array/string `profiles` de `settings.json`;
3. array/string `profile` de `settings.json`;
4. `default`.

Argumentos CLI aceitos:

```text
-profile=dev
-p=dev
-profile dev
-p dev
```

Valores separados por vírgula são divididos, espaços são removidos e duplicatas são
eliminadas preservando a primeira ocorrência. Uma classe ou método produtor com `@Profile`
é ativo quando pelo menos um valor da anotação está na lista ativa.

O profile do produtor é filtrado **antes** de o método entrar no grafo. Um método inativo
não é invocado, não registra bean e não participa da resolução de dependências. Quando
classe `@Configuration` e método possuem `@Profile`, ambos precisam estar ativos; os
valores dentro de cada anotação usam correspondência “ou”.

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

## Criação e injeção

### Escopos

| Declaração | Escopo efetivo |
|---|---|
| classe `@Component`/`@Service` | prototype |
| classe com `@Singleton` | singleton |
| método produtor `@Component`/`@Service` | singleton |
| produtor com `@BeanDefinition(STATIC)` | singleton; `STATIC` é default |
| produtor com `@BeanDefinition(INSTANCE)` | prototype, recriado pelo container |
| objeto passado a `registerDependency` | singleton |
| `RegistrationFunction<T>` | prototype |
| classe `@Async` | uma construção assíncrona compartilhada pelo registro |

Para prototype, `@PostCreation` e a injeção ocorrem em cada criação.

### Escolha de construtor

Na criação normal de um bean:

1. se existir construtor sem argumentos, ele é usado;
2. caso contrário, um construtor `@MainConstructor` é preferido;
3. sem `@MainConstructor`, o primeiro construtor devolvido pela reflexão é usado;
4. os parâmetros são resolvidos pelo container;
5. se esse caminho falhar, o código tenta novamente um construtor vazio.

A ordem de construtores devolvida por reflexão não é um contrato. Para previsibilidade:

- não declare construtor vazio se deseja forçar injeção por construtor;
- quando houver mais de um construtor parametrizado, marque exatamente um com
  `@MainConstructor`;
- use `@Value` no parâmetro para settings;
- use `@Qualifier("nome")` para escolher uma implementação.

`@Inject(qualifier = "...")` em parâmetro não participa da escolha de qualifier no caminho
atual. Em campos, `@Qualifier` tem precedência sobre `@Inject.qualifier`.

### Ordem dentro do objeto

A criação de um objeto segue:

1. construtor;
2. injeção dos campos `@Inject` e `@Value`;
3. criação do proxy AOP, quando habilitada e possível;
4. métodos `@PostCreation` em `order` crescente.

`@PostCreation` deve ser sem parâmetros. A implementação torna o método acessível e ignora
seu retorno. Uma falha é registrada no log e não aborta explicitamente a criação.

### Estratégia de injeção de campos

A estratégia afeta somente o processamento dos campos `@Inject` e `@Value` de uma mesma
instância:

- `SEQUENTIAL`: um campo por vez na thread atual;
- `PARALLEL`: todos os campos em tarefas concorrentes;
- `ADAPTIVE`: sequencial com até 10 campos e paralela com mais de 10.

Na execução paralela, até 10 campos usam uma thread virtual por tarefa; acima de 10 usam o
executor principal. O container aguarda todas as tarefas antes de continuar a criação.
Isso não cria uma ordem entre efeitos colaterais das injeções.

Precedência da configuração:

1. `setInjectionStrategy(...)` antes de `load()`;
2. `dependencyContainer.injectionStrategy` em `AppSettings`;
3. `ADAPTIVE`.

Uma chamada programática com `null` significa `ADAPTIVE` e ainda bloqueia a configuração
declarativa. Valor declarativo desconhecido gera warning e usa `ADAPTIVE`.

### Falta de dependência

Os lookups internos e públicos tendem a registrar o erro e retornar `null`:

- campo: a atribuição falha ou recebe `null`; a exceção é capturada e logada, e o boot pode
  continuar;
- construtor/método produtor: o argumento pode ser `null`;
- `getDependency`: retorna `null` após logar;
- wrapper lazy/async: a falha aparece quando o valor é solicitado ou o future conclui.

Portanto, uma referência não nula após o boot deve ser validada pela aplicação. Não use
“o processo subiu” como prova de que toda injeção foi satisfeita.

## Ordem de resolução

### Qualifier e primary

Para `getDependency(Tipo.class, qualifier)`:

1. o mapa do tipo indexado é obtido;
2. se o qualifier é vazio ou `default`, o índice de `@Primary` é consultado;
3. sem primary, é feita busca exata pelo qualifier;
4. sem registro, o lookup falha e retorna `null` após log.

Consequências:

- `@Primary` ganha até sobre um registro chamado `default` em lookup default;
- qualifier explícito nunca cai para primary nem para outro qualifier;
- dois primaries indexados para o mesmo tipo causam `InvalidClassRegistrationException`;
- sem primary e sem entrada `default`, múltiplos beans qualificados não são escolhidos
  automaticamente;
- não há resolução “pelo único candidato” se o qualifier solicitado não existir.

Em classes, use `@Qualifier` para nomear o registro. Os atributos
`@Component(qualifier = ...)` e `@Service(qualifier = ...)` não são lidos pelo caminho de
classe confirmado. Em métodos produtores, esses atributos são lidos.

Não combine `@Qualifier` e `@Primary` no mesmo bean: em classes, qualifier é avaliado
primeiro e impede a marcação primary. Em um produtor padrão anotado diretamente com
`@Component` ou `@Service`, o qualifier dessa anotação é devolvido antes que
`@Qualifier` ou `@Primary` sejam consultados. Portanto, nomeie o produtor pelo atributo
`qualifier` de `@Component`/`@Service` e não use `@Primary` nesse produtor; prefira uma
classe componente `@Primary`.

### Registro por tipo

Um bean é indexado por:

- sua classe concreta;
- sua superclasse direta, se aplicável;
- suas interfaces diretamente implementadas.

Não há busca geral por distância na hierarquia. Aliases com o mesmo `(tipo, qualifier)`
podem se sobrescrever durante registro automático, cuja execução por camada é paralela.
Esse caso não possui vencedor estável: atribua qualifiers diferentes ou um primary único.

Um objeto registrado manualmente antes de `load()` bloqueia o registro automático da
mesma classe concreta. Isso não deve ser generalizado como prioridade para todo alias de
interface, pois aliases podem colidir depois.

### Tipos parametrizados suportados

Campos e parâmetros podem usar wrappers reconhecidos pelo container:

- `LazyDependency<T>` para resolução adiada;
- `AsyncComponent<T>` para bean criado em background;
- `CompositeDependency<T>` para coleção de registros do tipo;
- `AtomicReference<T>`, `WeakReference<T>` e `SoftReference<T>`.

`AsyncComponent` deve terminar em um tipo concreto; aninhamento parametrizado dentro dele
é rejeitado. Use os wrappers somente com um argumento de tipo reificável simples.

## Configurações e beans produtores

Uma classe `@Configuration` é instanciada pelo container. Um método só é produtor quando
possui `@Component`, `@Service` ou uma anotação que tenha `@Component` como meta-anotação.

`@BeanDefinition` **não torna o método produtor sozinho**. Ele apenas altera o escopo do
método que já foi reconhecido:

```java
@Configuration
public class ClientConfig {

    @Component
    @BeanDefinition(proxyType = BeanDefinition.ProxyType.STATIC)
    public ApiClient apiClient(AppSettings settings) {
        return new ApiClient(settings.getString("api.url", "http://localhost"));
    }
}
```

Produtores são separados em duas fases: os que não dependem de serviços rodam antes da
criação dos componentes; os que dependem rodam depois. Dentro da fase, o grafo de beans
ordena produtores por dependência, mas não documenta ordem total entre independentes.

O retorno `null` não é registrado. Uma exceção no produtor falha a carga do container.

## Settings

O `JsonAppSettings` padrão carrega `settings.json` do classpath. Depois aplica
`settings.<profile>.json` para cada profile ativo, em ordem, com override recursivo.

- base ausente, vazia, inválida ou com raiz não objeto: log e configuração vazia;
- profile ausente: ignorado;
- `getString/getInt/getLong/getDouble/getBoolean`: usa o default em ausência/valor
  incompatível conforme a conversão;
- `getObject`: tenta desserializar; em ausência/erro, tenta construtor default e pode
  retornar `null`;
- `@Value` não precisa de `@Inject`;
- registro posterior via `AppSettingsRegistry` não reinjeta campos `@Value` existentes.

O registry externo usa `KEEP` por default. A política default é:

```json
{
  "settingsRegistry": {
    "enabled": true,
    "required": true,
    "allowedModes": ["KEEP"],
    "failOnPolicyOverride": true
  }
}
```

`String` e `Path` representam um documento. `Class` e `ClassLoader` exigem um
`settings.json` base quando `settingsRegistry.required` é `true` (o default) e carregam
profiles opcionais. Com `required: false`, a ausência do base é ignorada e profiles
disponíveis ainda são carregados; se nenhum recurso existir, o registro não altera o
estado. Falha de parse/leitura ou tentativa proibida não altera o estado anterior,
conforme os testes do registry.

## Assíncrono, eventos e scheduler

### Métodos `@Async`

Em métodos de negócio, dependem de:

- bean gerenciado e chamado por sua instância/proxy fornecido pelo container;
- AOP habilitado;
- `@EnableAsync`, que importa o `AsyncAspect`;
- ausência de `@DisableAop` no caminho relevante.

O aspecto não aplica `@Async` a métodos de classes `@Configuration`. Retornos aceitos:

| Retorno declarado | Resultado para o chamador |
|---|---|
| `void`/`Void` | retorna imediatamente; falha ocorre na thread do executor |
| `CompletableFuture<T>`/`CompletionStage<T>` | future que achata o stage retornado |
| `Future<T>` | future do executor; um future retornado pelo método é aguardado na tarefa |
| `AsyncResult<T>` | wrapper com callbacks e `await()` |

Outro retorno lança `AsyncMethodException` quando interceptado. O executor default é
`ForkJoinPool.commonPool()`; um bean `AsyncExecutorFactory` pode substituí-lo.

Um método produtor `@Async` dentro de `@Configuration` não usa o aspecto e não depende de
`@EnableAsync`. O container cria um future no executor principal, registra imediatamente
`AsyncComponent<T>` e continua a passagem sem aguardar a factory. Na prática, a anotação
funciona como uma forma abreviada de `AsyncRegistrationFunction<T>` com o executor do
container.

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

O grafo reconhece o tipo genérico de `AsyncComponent<T>` e ordena o consumidor depois do
**registro do wrapper**, não depois da conclusão de `T`. Parâmetro direto `T` em outro
produtor não é válido quando só existe o produtor async: o carregamento falha com uma
mensagem indicando `AsyncComponent<T>`, em vez de invocar a factory consumidora com `null`.

Falha, retorno `null` ou exceção da factory conclui `AsyncResult<T>` excepcionalmente;
`await()` propaga `CompletionException` e callbacks podem tratar o erro. Como o registro já
terminou, essa falha não desfaz retroativamente o boot ou um lote externo publicado. No
fluxo externo, a tarefa fica associada ao registro para cancelamento e teardown.

Produtor `@Async` deve retornar diretamente `T`; não aceita `void`, retorno
`RegistrationFunction`/`AsyncRegistrationFunction` nem escopo
`@BeanDefinition(INSTANCE)` ou `@Primary`. Qualifiers são suportados. Use
`AsyncRegistrationFunction<T>` quando precisar escolher explicitamente executor, supplier,
tipo de referência ou qualifier.

### Classes `@Async`

Também precisam ser `@Component`/`@Service`. A criação começa no executor principal e o
registro exposto é `AsyncComponent<T>`:

```java
@Inject
private AsyncComponent<CacheWarmup> warmup;
```

O objeto real não é indexado diretamente como `T`. `await()` bloqueia e propaga falha como
`CompletionException`; callbacks mantêm o fluxo não bloqueante.

### Eventos

No container principal, o scan inicial considera somente registros cujo tipo indexado tem
`@Event` direta ou como meta-anotação. Assim, o padrão previsível é:

```java
@Event
@Singleton
@Component
public class OrderListeners {
    @EventListener(order = 10)
    public void onOrder(OrderPlaced event) {}
}
```

Com um parâmetro, ele é o evento. Com mais de um, exatamente um deve ter `@Event`; os demais
são resolvidos pelo container e podem usar `@Qualifier` ou `@Inject.qualifier`.

- `async = false`: executa na thread de `publish`; exceção propaga.
- `async = true`: submete ao executor principal; `publish` não aguarda e falha é logada.
- bindings são percorridos por `order` crescente.
- para async, a ordem de submissão não garante ordem de término.
- empates não têm garantia útil de ordenação.
- `publish(null)` é ignorado.

Na carga externa em runtime, componentes com método `@EventListener` são registrados mesmo
sem `@Event`; essa diferença é coberta pelos testes. Prototype externo não ganha listener
no load porque não há instância persistente. No container principal, um prototype `@Event`
pode ter uma instância criada apenas para o scan, diferente da instância resolvida depois.

### Scheduler

O scheduler só existe com `@EnableSchedule` no bootable. Cada classe `@Schedule` descoberta
é instanciada via `container.newInstance`, mesmo que não seja `@Component`.

Um método é elegível quando tem `@ScheduleMethod` e zero parâmetros. A visibilidade é
tornada acessível; retorno é ignorado. Defaults efetivos:

- `threads = 2`, com mínimo efetivo 2;
- `timeUnit = MILLISECONDS`;
- `time <= 0` vira `1000`;
- `startDelay <= 0` vira `0`;
- `periodic = true` usa `scheduleAtFixedRate`;
- `periodic = false` agenda uma execução.

Workers têm nome `App-Scheduler-Worker` e são daemon. Exceção da tarefa é capturada e
logada; no modo periódico, a captura permite novas execuções. O shutdown apenas chama
`shutdown()`, sem `awaitTermination` confirmado.

## Erros e propagação

| Situação | Comportamento confirmado |
|---|---|
| main/bootable/`@OnBoot` inválido | `InvalidBootException` síncrona antes da BootThread |
| erro em `BEFORE_ALL` | `InvalidBootException` para o chamador de `doRun` |
| erro em `container.load()` no boot | encaminhado na BootThread como causa de `InvalidBootException` |
| erro em `@OnBoot`, runner ou hook pós-load | capturado, `AFTER_ALL` é tentado e erro vai ao handler |
| erro em `@PostCreation` | logado; criação continua |
| injeção de campo ausente/falha | logada; campo pode permanecer `null` |
| lookup público ausente | logado e retorna `null` |
| listener síncrono | propaga de `publish` |
| listener assíncrono | logado no future; não propaga a `publish` |
| schedule | logado dentro da tarefa |
| método `@Async` com future | future conclui excepcionalmente |
| método `@Async void` | falha não retorna ao chamador; segue a política do executor/thread |
| produtor `@Async` de `@Configuration` | o wrapper é registrado; falha posterior conclui `AsyncResult` excepcionalmente e não reverte a carga concluída |
| carga inicial do container | encapsula em `UnloadError`; rollback não garantido |
| carga externa em lote | falha desfaz o lote; comportamento coberto por testes |
| `@PreDestroy` global ou externo | falha logada e os demais métodos continuam |

Após o container carregar, uma classe `@ExceptionHandler` encontrada pode assumir o
tratamento global. A seleção usa `parallelStream().findFirst()`, portanto mantenha no
máximo uma. Um `@ControllerAdvice` é preparado em thread separada; se um erro chegar antes
da conclusão, o tratamento aguarda o future do scanner.

Em `@ExceptionHandler`, métodos `@OnException(Tipo.class)` são indexados e o tipo exato
vence; sem exato, o invocador procura o tipo atribuível mais próximo. Em
`@ControllerAdvice`, métodos `@HandleException({Tipo.class})` seguem tipo exato e depois
um compatível, mas não há desempate por maior especificidade. Os parâmetros aceitos nesses
métodos são `Throwable`/subtipo e `Thread`; não há injeção de outros beans.

Sem handler customizado válido, Kernon delega ao `UncaughtExceptionHandler` que existia
antes de `doRun`; se não havia um, imprime stack trace.

## Shutdown e descarga externa

O boot gerenciado instala uma única shutdown hook estática por JVM:

1. executa hooks `ON_CLOSE`;
2. chama `shutdown()` no scheduler;
3. chama `container.unload()`.

No caminho global, `unload()` reúne as instâncias externas possuídas e todos os singletons
do container principal, elimina duplicatas por identidade e executa `@PreDestroy` uma vez
por objeto antes de limpar os registros. Dentro do mesmo objeto, maior `order` executa
primeiro. Para beans proxied, métodos equivalentes na classe proxy e na hierarquia são
deduplicados por nome e tipos dos parâmetros. A ordem entre singletons normais
independentes não é um contrato.

Beans prototype não recebem `@PreDestroy` automático: cada resolução cria uma instância
que não fica retida para teardown. Instâncias criadas manualmente por `newInstance` também
continuam sob responsabilidade do chamador.

Para `loadExternal(Collection<Class<?>>)`:

- o container principal precisa estar carregado;
- classes nulas são rejeitadas;
- só classes concretas elegíveis são registradas;
- o lote é publicado de forma transacional e revertido em falha;
- unload seletivo rejeita a remoção se outro componente externo ativo depende do alvo;
- listeners, tarefas async e caches associados são removidos;
- `@PreDestroy` executa uma vez por instância possuída, em ordem inversa da criação;
- instâncias criadas manualmente por `newInstance` não são possuídas nem destruídas pelo
  registro externo.

Consulte também [Limites e lacunas conhecidas](KNOWN_LIMITATIONS.md).

## Matriz de verificação

As regras acima foram confrontadas com a implementação e com os seguintes testes:

| Área | Evidência automatizada |
|---|---|
| boot gerenciado e retorno de `doRun` | `ManagedApplicationIntegrationTest` em JVM filha |
| hooks, runner e falha de `@OnBoot` | `ManagedApplicationIntegrationTest` |
| scheduler e thread dedicada | `ManagedApplicationIntegrationTest` |
| AOP com métodos `@Async` | `ManagedApplicationIntegrationTest` |
| shutdown hook e `@PreDestroy`, inclusive bean proxied | `ManagedApplicationIntegrationTest` |
| `@PreDestroy` no unload global e idempotência | `GlobalLifecycleTest` |
| primary, qualifiers e rollback de primary duplicado | `ExternalLoadTest` |
| `@Profile` e `@Async` em métodos produtores | `ProducerMethodIntegrationTest` e `ExternalLoadTest` |
| carga/descarga externa | testes do pacote `dtm.di.external` |
| estratégia de injeção | `InjectionStrategySettingsTest` |
| settings e registro externo | `JsonAppSettingsRegistryTest` |

O cenário gerenciado usa um processo Java separado. Isso valida a instalação e execução do
shutdown hook real e evita que o estado estático do bootstrap contamine outros testes.
Concorrência sem ordenação contratual continua descrita como tal; um teste não transforma
uma ordem incidental em garantia pública.
