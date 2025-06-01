# Classe Managed Application Startup - Guia de Uso

## 📌 Índice
1. [Visão Geral](#-visão-geral)
2. [Visão Arquitetural](#-visão-geral-arquitetural)
3. [Configuração Básica](#-configuração-básica)
4. [Boot Externo](#-boot-externo)
5. [Anotações de Ciclo de Vida](#-anotações-de-ciclo-de-vida)
6. [Gerenciamento de Dependências](#-gerenciamento-de-dependências)
7. [Configurações Avançadas](#-configurações-avançadas)
8. [Solução de Problemas](#-solução-de-problemas)
9. [Exemplo Completo](#-exemplo-completo)

## Visão Geral
O `ManagedApplicationStartup` é um sistema de inicialização gerenciada para aplicações Java que oferece:
- Injeção de dependência automática
- Hooks de ciclo de vida
- Configuração simplificada
- Suporte a AOP (Aspect-Oriented Programming)


##  Visão Geral Arquitetural

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


![Diagrama de Arquitetura](/docs/imgs/arquitetura_managed_app.png)

## Configuração Básica

### 1. Classe Principal
Anote sua classe principal(a com o main(String[] args)) com `@ApplicationBoot`:

## Sintaxe Básica

```java
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
// Projeto A
@ApplicationBoot(SharedBootConfig.class)

// Projeto B
@ApplicationBoot(SharedBootConfig.class)
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

## `@LifecycleHook` - Controle Fino do Ciclo de Vida

### Eventos Disponíveis e Seus Momentos Exatos

| Evento                  | Fase de Execução                | Contexto Disponível                          | Uso Típico                          |
|-------------------------|---------------------------------|---------------------------------------------|-------------------------------------|
| `BEFORE_ALL`            | **Primeira etapa** do boot      | Container DI ainda não inicializado         | - Configurar variáveis de ambiente<br>- Validar requisitos do sistema |
| `AFTER_CONTAINER_LOAD`  | Após **DI completo** mas antes do `@OnBoot` | Container DI pronto, mas app não iniciado | - Validar dependências injetadas<br>- Configurar proxies dinâmicos |
| `AFTER_STARTUP_METHOD`  | Imediatamente após `@OnBoot`    | App inicializado mas antes de serviços rodando | - Health checks iniciais<br>- Registrar métricas |
| `AFTER_ALL`             | **Última etapa** do processo    | Todos serviços ativos                       | - Liberar recursos temporários<br>- Registrar fim do boot |

### Exemplo Prático com Todos Eventos

```java
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

## Fluxo de Execução Garantido

### Ordem Estrita de Processamento
1. `BEFORE_ALL`  
   → 2. `AFTER_CONTAINER_LOAD`  
   → 3. `@OnBoot`  
   → 4. `AFTER_STARTUP_METHOD`  
   → 5. `AFTER_ALL`

###  Regras Críticas

- **Idempotência**:
  ```diff
  + Métodos devem produzir o mesmo resultado em múltiplas execuções.
  - Evite operações não determinísticas.
  ```
- **Performance**: Cada hook adiciona tempo de inicialização → Mantenha operações essenciais

# Diagrama de Fluxo
![Diagrama de Fluxo](/docs/imgs/fluxo_managed_app.png)