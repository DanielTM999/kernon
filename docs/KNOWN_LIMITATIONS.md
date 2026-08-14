# Limites e lacunas conhecidas

Esta lista separa limitações confirmadas de pontos que não possuem evidência suficiente.
Ela documenta o estado atual; não representa comportamento desejado.

## Limitações confirmadas

- `ManagedApplication.doRun(...)` retorna antes do fim do boot. Não há API de readiness ou
  future público do boot.
- O registro de schedules é disparado em background antes de `@OnBoot` e não é aguardado.
- A carga inicial marca o container como carregado antes de concluir todo o registro e não
  implementa rollback atômico. Falha pode deixar estado parcial.
- Injeção ausente normalmente é logada e pode resultar em `null`; não é fail-fast.
- `@Component.qualifier` e `@Service.qualifier` não são lidos em classes. Use
  `@Qualifier`; os atributos funcionam em métodos produtores.
- Em produtor padrão, `@Qualifier` e `@Primary` diretos são consultados depois do qualifier
  de `@Component`/`@Service` e, por isso, não produzem a seleção esperada. Use o atributo
  qualifier da anotação produtora ou uma classe componente `@Primary`.
- `@Inject.qualifier` não é lido em parâmetros de construtor/factory. Use `@Qualifier`.
- `@BeanDefinition` sozinho não identifica um método produtor; combine com `@Component`
  ou `@Service`.
- `@Configuration.order`, `@Configuration.lazy` e `@BeforeInitialization` não são
  consultados pela implementação atual.
- No container principal, `@EventListener` só entra no scan inicial se a classe também
  estiver marcada com `@Event`. A carga externa possui regra diferente.
- Um listener prototype `@Event` do container principal pode ser registrado em uma
  instância criada apenas para o scan, diferente das instâncias resolvidas depois.
- Filtros `@PackageScanIgnore` aplicados ao container padrão são substituídos antes da
  descoberta efetiva. Veja o documento específico.
- O shutdown do scheduler chama `shutdown()` sem esperar terminação.
- O container e vários estados do boot são estáticos por JVM. Múltiplos boots no mesmo
  processo não têm isolamento documentado.

## Ordem não garantida

Não dependa de ordem entre:

- componentes sem relação no grafo e pertencentes à mesma camada;
- múltiplos `ApplicationRunner`;
- múltiplos handlers globais descobertos por scan paralelo;
- hooks ou métodos de lifecycle com o mesmo `order`;
- listeners com o mesmo `order`;
- conclusão de listeners async, beans async ou schedules;
- aliases concorrentes com o mesmo `(interface, qualifier)`.

Declare dependências reais, qualifiers únicos e orders distintos quando a sequência for
parte do requisito. Mesmo com `order`, tarefas assíncronas só têm ordem de submissão, não
de conclusão.

## Não documentado atualmente

- Não há repositório Maven público ou procedimento de publicação/consumo documentado.
- Não há contrato de compatibilidade semântica entre versões.
- Não há política documentada de thread-safety para beans do usuário.
- Não há contrato de timeout para boot, construção de beans, runners, hooks ou shutdown.
- Não há política de retry automática confirmada.
- Não há garantia de readiness, health check ou sinal de “boot concluído”.
- Não há contrato de ordenação total da descoberta de classes.
- Não há contrato para múltiplos boots ou múltiplos containers padrão na mesma JVM.
- Não foi possível confirmar no código uma finalidade runtime para
  `@BeforeInitialization`, `Configuration.order` e `Configuration.lazy`.

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
