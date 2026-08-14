# Kernon

Kernon é um framework Java 21 para aplicações que precisam de:

- descoberta e injeção de dependências;
- boot gerenciado com hooks de ciclo de vida;
- AOP opcional;
- execução assíncrona, eventos e tarefas agendadas;
- settings JSON com profiles;
- carga e descarga de componentes externos em runtime.

Ele é indicado para aplicações Java pequenas ou modulares que aceitam descoberta por
classpath e anotações próprias do framework. Não é um servidor HTTP, ORM, sistema de
mensageria ou substituto completo para frameworks de aplicação maiores.

Este repositório declara as coordenadas Maven `dtm.di:kernon:1.2.0` e exige Java 21.
A publicação do artefato em um repositório Maven público não está documentada atualmente.

## Documentação

- [Comportamento do framework](docs/FRAMEWORK_BEHAVIOR.md): boot, container, resolução,
  concorrência, erros, settings e shutdown.
- [Referência de anotações](docs/ANNOTATIONS.md): defaults, dependências, combinações e
  restrições confirmadas.
- [Controle de package scan](docs/PackageScanIgnoreReadMe.md): defaults e limitação atual.
- [Limites e lacunas conhecidas](docs/KNOWN_LIMITATIONS.md): comportamentos nos quais o
  usuário não deve confiar.

Leia ao menos a referência de comportamento antes de usar Kernon em um processo que
precise de ordem de inicialização ou tratamento de falhas previsíveis.

## Exemplo mínimo

### 1. Ponto de entrada

`@ApplicationBoot` exige uma classe em `value`. Essa classe é o **bootable**: é nela que
Kernon procura `@OnBoot`, hooks e opções de boot.

```java
package com.example;

import dtm.di.annotations.boot.ApplicationBoot;
import dtm.di.application.startup.ManagedApplication;

@ApplicationBoot(AppBoot.class)
public final class Main {
    public static void main(String[] args) {
        ManagedApplication.doRun(true, args);
    }
}
```

O primeiro argumento habilita somente os logs do bootstrap. O default dos overloads que
não recebem esse argumento é `false`.

### 2. Classe de boot

```java
package com.example;

import dtm.di.annotations.boot.OnApplicationFail;
import dtm.di.annotations.boot.OnBoot;

public final class AppBoot {

    @OnBoot
    public static void start(GreetingService greetings, String[] args) {
        System.out.println(greetings.message("Kernon"));
    }

    @OnApplicationFail
    public static void fail(Throwable error, Thread thread) {
        System.err.println("Boot falhou em " + thread.getName() + ": " + error.getMessage());
    }
}
```

`@OnBoot` pode receber `String[]`, o `DependencyContainer` exato ou dependências resolvidas
pelo container. Use apenas um método `@OnBoot`: se houver mais de um, a escolha depende da
ordem de reflexão e não é garantida.

### 3. Serviço gerenciado

```java
package com.example;

import dtm.di.annotations.Service;
import dtm.di.annotations.Singleton;

@Singleton
@Service
public class GreetingService {
    public String message(String name) {
        return "Olá, " + name;
    }
}
```

Classes `@Component` e `@Service` são **prototype por default**: cada resolução pode criar
outra instância. Use `@Singleton` quando a mesma instância deve ser reutilizada.

## O que acontece no boot

A ordem externa confirmada é:

1. `ManagedApplication.doRun(...)` identifica a classe principal e o bootable.
2. `BEFORE_ALL` executa sincronamente na thread que chamou `doRun`.
3. Uma thread não daemon chamada `BootThread` carrega o container.
4. `AFTER_CONTAINER_LOAD` executa depois de `container.load()`.
5. O registro das tarefas `@Schedule` é disparado em background.
6. O método `@OnBoot` executa na `BootThread`.
7. Os beans `ApplicationRunner` executam, um por vez, na `BootThread`.
8. `AFTER_STARTUP_METHOD` executa.
9. `AFTER_ALL` executa no bloco `finally`, inclusive quando uma fase anterior falha.

Importante: `doRun(...)` **não aguarda o fim do boot**. Ele retorna depois de iniciar a
`BootThread`. Não trate o retorno de `doRun` como sinal de aplicação pronta.

Detalhes e ressalvas estão em [Comportamento do framework](docs/FRAMEWORK_BEHAVIOR.md).

## Resolução em uma frase

Para uma solicitação `(tipo, qualifier)`, Kernon:

1. consulta o índice `@Primary` quando o qualifier solicitado é `default`;
2. caso não haja primary, procura exatamente o qualifier solicitado;
3. instancia ou devolve o registro encontrado;
4. em lookup público ausente, registra erro no log e retorna `null`.

`@Qualifier` explícito não cai para `@Primary`. Mais de um primary para o mesmo tipo causa
falha de registro. Não há desempate garantido entre registros que colidem por alias de
interface; use qualifiers distintos.

## Defaults essenciais

| Área | Default confirmado | Configurável por |
|---|---|---|
| Java | 21 | build da aplicação, respeitando a compatibilidade do artefato |
| logs de boot | desabilitados | argumento `log` de `doRun` |
| AOP no boot gerenciado | habilitado | `@DisableAop` no bootable |
| AOP no container direto | desabilitado | `enableAOP()` antes de `load()` |
| escopo de `@Component`/`@Service` | prototype | `@Singleton` |
| bean de método produtor | singleton | `@BeanDefinition(proxyType = INSTANCE)` |
| qualifier | `"default"` | `@Qualifier`; em método produtor, atributo de `@Component`/`@Service` |
| profile | `default` | argumentos/perfis, CLI ou `settings.json` |
| injeção de campos | `ADAPTIVE` | API ou `dependencyContainer.injectionStrategy` |
| `ADAPTIVE` | sequencial até 10 campos; paralela acima de 10 | `PARALLEL` ou `SEQUENTIAL` |
| `@EventListener` | síncrono, `order = 0` | `async` e `order` |
| scheduler | desabilitado | `@EnableSchedule` no bootable |
| threads do scheduler | 2; valores menores também viram 2 | `@EnableSchedule(threads = ...)` |
| `@ScheduleMethod.timeUnit` | milissegundos | atributo da anotação |
| intervalo de schedule `time <= 0` | 1000 unidades | `time` positivo |
| executor de método `@Async` | `ForkJoinPool.commonPool()` | bean `AsyncExecutorFactory` |
| executor de produtor `@Async` | executor principal do container | use `AsyncRegistrationFunction<T>` para escolher outro |
| arquivo de settings | `settings.json` no classpath | bean `AppSettings` ou `JsonAppSettings` customizado |

## Regras que evitam surpresas

- Construtores são injetados sem `@Inject`; campos precisam de `@Inject`.
- Para qualifier em parâmetro de construtor, use `@Qualifier`, não
  `@Inject(qualifier = ...)`.
- Em `@Configuration`, um método produtor precisa de `@Component` ou `@Service`.
  `@BeanDefinition` sozinho não faz o método ser descoberto.
- `@Profile` pode ser aplicado à classe `@Configuration` e a cada método produtor. Um
  produtor inativo não entra no grafo, não é invocado e não registra bean.
- Um produtor `@Async` registra imediatamente `AsyncComponent<T>` e executa a factory no
  executor principal sem bloquear o carregamento. Outro produtor deve recebê-lo como
  `AsyncComponent<T>`; parâmetro direto `T` falha com orientação explícita.
- Classes `@Async` também precisam ser componentes e são resolvidas como
  `AsyncComponent<T>`, não diretamente como `T`.
- Métodos `@Async` dependem de `@EnableAsync`, AOP ativo e chamada pela instância
  gerenciada.
- No container principal, a classe de um listener declarativo precisa de `@Event`, além
  do método `@EventListener`, para entrar no scan inicial.
- `@ScheduleMethod` só tem efeito em uma classe `@Schedule` e com
  `@EnableSchedule` no bootable.
- Ordem entre componentes independentes, runners, métodos com o mesmo `order` e tarefas
  paralelas não é garantida.
- Falha de injeção em campo é logada e o campo pode permanecer `null`; ela não garante
  falha do boot.
- O shutdown hook chama `ON_CLOSE`, encerra o scheduler e descarrega o container.
  `@PreDestroy` executa uma vez para cada singleton gerenciado; prototypes não possuem
  descarte automático porque o container não retém suas instâncias. Beans proxied também
  são deduplicados por objeto e assinatura de método.

## Uso direto do container

O container pode ser usado sem o boot gerenciado:

```java
DependencyContainer container = DependencyContainerStorage.getInstance(Main.class);
container.setInjectionStrategy(InjectionStrategy.SEQUENTIAL);
container.enableAOP(); // opcional; o default direto é AOP desligado
container.load();

GreetingService service = container.getDependency(GreetingService.class);
container.unload();
```

`load()` é síncrono para o chamador quanto às etapas de registro, embora crie componentes
da mesma camada em paralelo. Classes componente e métodos produtores `@Async` registram
`AsyncComponent<T>` e deixam a construção do objeto real em background. O container
padrão é mantido em um registro estático; trate-o como um container por processo e evite
múltiplos boots gerenciados na mesma JVM.

## Erros e suporte operacional

- Falhas antes da criação da `BootThread`, inclusive em `BEFORE_ALL`, são lançadas ao
  chamador de `doRun`.
- Falhas posteriores são encaminhadas ao handler configurado na thread de boot.
- `@OnApplicationFail` é o fallback simples; após o container carregar, um
  `@ExceptionHandler` descoberto pode substituí-lo.
- Sem handler do Kernon, o framework delega ao `UncaughtExceptionHandler` anterior da JVM
  ou imprime a stack trace.
- A carga inicial do container não oferece rollback atômico documentado. A carga externa
  em lote possui rollback coberto por testes.

Consulte a [tabela completa de erros](docs/FRAMEWORK_BEHAVIOR.md#erros-e-propagacao) antes
de definir políticas de retry ou readiness.

## Verificação do projeto

A suíte inclui testes de integração em JVM isolada para boot gerenciado, hooks, falha de
boot, scheduler, `ApplicationRunner`, AOP com `@Async` e shutdown real. Execute:

```shell
mvn test
```

Também há regressões para `@PreDestroy` global, rejeição transacional de dois beans
`@Primary` do mesmo tipo e produtores com `@Profile`/`@Async` nos fluxos inicial e externo.
