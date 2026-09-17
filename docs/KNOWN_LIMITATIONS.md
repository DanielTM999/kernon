# Limites e lacunas conhecidas

Esta lista documenta o estado atual do código; não representa comportamento desejado.
Cada item foi confirmado por leitura do código ou por teste. Última revalidação: 2026-09-17.

## Limitações confirmadas

### Boot e ciclo de vida

- `ManagedApplication.doRun(...)` retorna antes do fim do boot. Não há API de readiness ou
  future público do boot.
- O registro de schedules é disparado em background antes de `@OnBoot` e não é aguardado.
- A carga inicial marca o container como carregado antes de concluir todo o registro. A flag
  antecipada é estrutural — os passos seguintes de `load()` dependem dela —, mas não há rollback
  atômico: uma falha no meio deixa estado parcial.
- O container e vários estados do boot são estáticos por JVM (`StaticContainer`). Múltiplos
  boots no mesmo processo não têm isolamento documentado. Em particular, `unload()` reatribui
  `classFinderConfigurations` a partir dos settings, descartando ajustes programáticos de scan
  feitos antes (entre eles os de `@PackageScanIgnore`).

### Injeção

- Dependência **ausente** é logada e resulta em `null`; não é fail-fast. Isso não vale para
  **ambiguidade**: com vários candidatos e nenhum desempate, o padrão
  `dependencyContainer.ambiguityPolicy=FAIL_FAST` lança `AmbiguousDependencyException`.

### Produtores

- Um produtor não-singleton só registra o bean se a classe retornada tiver construtor vazio
  público (`registerExternalBeenNoSinglenton`). Lambdas e classes anônimas não atendem a isso e
  falham no registro. Coberto por `NonSingletonGenericProducerTest`.

### Anotações sem efeito

- `@Configuration.order`, `@Configuration.lazy` e `@BeforeInitialization` não são consultados
  pela implementação atual. São atributos públicos que compilam e não fazem nada.

## Comportamento esperado, não limitação

- No container principal, `@EventListener` só entra no scan inicial se a classe também estiver
  marcada com `@Event`. A carga externa registra pela simples presença de métodos
  `@EventListener`. As duas regras estão documentadas no javadoc de `@Event`.
- Uma implementação genérica aberta (`class Generic<T> implements Processor<T>`) não produz chave
  genérica resolvida por si só — não há argumento concreto a indexar. Quando ela vem de um método
  produtor, o tipo declarado no retorno é usado e a resolução genérica funciona.

## Ordem não garantida

Não dependa de ordem entre:

- componentes sem relação no grafo e pertencentes à mesma camada;
- múltiplos `ApplicationRunner`;
- múltiplos handlers globais descobertos por scan paralelo;
- hooks ou métodos de lifecycle com o mesmo `order`;
- listeners com o mesmo `order`;
- conclusão de listeners async, beans async ou schedules;
- aliases concorrentes com o mesmo `(interface, qualifier, argumentos de tipo)` — quando as
  implementações diferem pelo argumento genérico, a resolução é determinística; o empate
  real cai na política `dependencyContainer.ambiguityPolicy`, cujo padrão é `FAIL_FAST`.

Declare dependências reais, qualifiers únicos e orders distintos quando a sequência for
parte do requisito. Mesmo com `order`, tarefas assíncronas só têm ordem de submissão, não
de conclusão.

## Não documentado atualmente

- Não há repositório Maven público ou procedimento de publicação/consumo documentado.
- Não há contrato de compatibilidade semântica entre versões.
- Não há política documentada de thread-safety para beans do usuário.
- Não há contrato de timeout para boot, construção de beans, runners, hooks ou shutdown.
- Não há política de retry automática confirmada.
- Não há contrato de ordenação total da descoberta de classes.

## Documentação desalinhada com o código

- `docs/PackageScanIgnoreReadMe.md` afirma que `loadSystemClasses()` substitui a configuração de
  scan antes de chamar o scanner, e conclui que `@PackageScanIgnore` não tem efeito prático. O
  código atual não faz essa substituição: `applyPeckageScan` muta a instância viva devolvida por
  `getClassFinderConfigurations()` e `loadSystemClasses()` apenas a repassa. Corrigir aquele
  documento exige um teste que comprove o efeito do filtro.

## Recomendações operacionais

- Trate injeções essenciais como invariantes e valide-as explicitamente no início da
  aplicação.
- Use um único `@OnBoot`, `@OnApplicationFail`, `@ExceptionHandler` e
  `@ControllerAdvice` por aplicação.
- Coloque todas as opções inspecionadas pelo boot na classe bootable.
- Prefira `InjectionStrategy.SEQUENTIAL` ao diagnosticar race conditions de injeção.
- Use `@PreDestroy` para liberação ordenada de recursos de singletons, mas não como única
  garantia de durabilidade: encerramento forçado da JVM pode não executar shutdown hooks.
- Não agende tarefa com delay zero se ela depende da conclusão de `@OnBoot` ou runners.
- Use `@Singleton` explicitamente quando identidade compartilhada for necessária.
- Evite efeitos colaterais dependentes de ordem em construtores e `@PostCreation` de beans
  independentes.
