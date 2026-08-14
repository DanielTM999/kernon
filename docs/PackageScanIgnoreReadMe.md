# Controle de package scan (`@PackageScanIgnore`)

`@PackageScanIgnore` declara termos a ignorar na descoberta. Ela é repetível por meio de
`@PackageScansIgnore` e deve ficar no bootable para ser lida pelo boot gerenciado.

## Parâmetros

| Parâmetro | Default | Comportamento no bootstrap |
|---|---|---|
| `ignorePackage` | array vazio | termos adicionados ou usados como substituição |
| `scanType` | `INCREMENT` | `INCREMENT` adiciona; `REPLACE` limpa e substitui |
| `scanElement` | `"default"` | somente `"jar"`, sem diferenciar maiúsculas, seleciona jars; qualquer outro valor seleciona packages |

Exemplo de intenção:

```java
@PackageScanIgnore(
    ignorePackage = {"com.example.generated"},
    scanType = PackageScanIgnore.ScanType.INCREMENT,
    scanElement = "package"
)
public final class AppBoot {}
```

## Defaults internos do scanner

O container padrão monta uma configuração que ignora estes termos de jar:

```text
lombok
byte-buddy
logback-classic
slf4j-api
classfinder
```

E estes termos de package:

```text
net.bytebuddy
ch.qos.logback
lombok
```

## Limitação atual do container padrão

No boot, os valores de `@PackageScanIgnore` são aplicados à configuração retornada pelo
container. Porém, no começo de `DependencyContainerStorage.loadSystemClasses()`, o
container padrão substitui essa configuração por uma nova instância antes de chamar o
scanner.

Consequência: **não foi confirmado efeito prático de `@PackageScanIgnore` no fluxo do
container padrão atual**. A inspeção do código indica que os filtros da anotação são
perdidos. Não use essa anotação como limite de segurança, isolamento ou garantia de
performance nessa implementação.

Um `ClassFinderDependencyContainer` customizado pode preservar a configuração, mas esse
contrato depende da implementação customizada e não é garantido pelo Kernon padrão.

## Risco de `REPLACE`

Quando a configuração é efetivamente respeitada por um container customizado,
`REPLACE` remove todos os defaults da lista escolhida. Isso pode reintroduzir bibliotecas
internas na varredura ou excluir pacotes essenciais, conforme os termos fornecidos.
Prefira `INCREMENT` salvo quando a substituição total for deliberada e testada.
