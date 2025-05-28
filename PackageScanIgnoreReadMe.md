
# Controle de Escaneamento de Pacotes (`@PackageScanIgnore`)

O framework permite excluir pacotes específicos do processo de escaneamento automático por meio da anotação `@PackageScanIgnore`. Isso ajuda a evitar a análise de classes irrelevantes, reduzindo a sobrecarga e prevenindo conflitos com bibliotecas externas.

---

## Exemplo básico de uso

```java
@PackageScanIgnore(
    ignorePackage = {"classfinder", "byte-buddy"},
    scanType = PackageScanIgnore.ScanType.INCREMENT,
    scanElement = "jar"
)
```
Esse exemplo evita escanear pacotes vindos de JARs externos que contenham `classfinder` ou `byte-buddy`.

---

## Diferenças entre os parâmetros

| Parâmetro        | Descrição                                                                                    |
|------------------|----------------------------------------------------------------------------------------------|
| `ignorePackage`  | Lista de pacotes (prefixos) que serão ignorados no escaneamento.                             |
| `scanType`       | Define a forma de exclusão:                                                                  |
|                  | - `INCREMENT`: adiciona os pacotes ignorados à lista atual.                                  |
|                  | - `REPLACE`: substitui completamente a lista de pacotes ignorados, ignorando os anteriores.  |
| `scanElement`    | Define o contexto do escaneamento:                                                           |
|                  | - `"jar"`: ignora os JARs inteiros que contenham os pacotes especificados (não abre o JAR).  |
|                  | - `"package"`: ignora os pacotes no classpath local, ou seja, no código fonte ou jar gerado. |

---

## Atenção: não ignore os pacotes internos do framework

⚠️ Ignorar os pacotes do próprio framework (por exemplo, `dtm.di`) com `ScanType.REPLACE` e `scanElement = "package"` pode fazer com que a aplicação inicialize "vazia", sem executar nenhuma lógica, e sem lançar erros explícitos.

### Exemplo inválido:

## ⚠️ Cuidado ao usar `ScanType.REPLACE`

Usar `ScanType.REPLACE` substitui completamente a lista de pacotes ignorados previamente definidos, inclusive os internos do próprio framework que podem ser essenciais para a inicialização correta da aplicação.

### Por que isso é perigoso?

- **Substituição total**: Todos os pacotes ignorados anteriormente — inclusive os padrões definidos internamente — são descartados.
- **Perda de escaneamento essencial**: Se você usar `REPLACE` e ignorar pacotes fundamentais (como `dtm.di`), o ciclo de vida da aplicação pode não ser iniciado corretamente.
- **Inicialização silenciosa**: A aplicação pode "subir", mas sem executar qualquer lógica anotada, resultando em um comportamento enganoso e difícil de depurar.


```java
@PackageScanIgnore(
    ignorePackage = {"dtm.di"},
    scanType = PackageScanIgnore.ScanType.REPLACE,
    scanElement = "package"
)
```

**O que acontece?**

- O escaneador ignora todo o pacote `dtm.di`.
- Como resultado, nenhuma classe anotada (como `@ApplicationBoot`, `@OnBoot`) será detectada.
- O ciclo de inicialização do framework não é executado.
- A aplicação "funciona", mas sem efeito algum — nenhuma lógica executada.

---



## Exemplos válidos para uso seguro

### Ignorar bibliotecas externas sem afetar o código local

```java
@PackageScanIgnore(
    ignorePackage = {"classfinder", "byte-buddy"},
    scanType = PackageScanIgnore.ScanType.INCREMENT,
    scanElement = "jar"
)
```

### Ignorar pacotes específicos no código local sem substituir completamente a lista

```java
@PackageScanIgnore(
    ignorePackage = {"com.exemplo.unused"},
    scanType = PackageScanIgnore.ScanType.INCREMENT,
    scanElement = "package"
)
```

---

## Boas práticas recomendadas

- **Prefira `INCREMENT` ao adicionar filtros** para não substituir acidentalmente as exclusões internas do framework.
- **Nunca ignore completamente os pacotes do framework (`dtm.di`, `dtm.internal`) no escaneamento local.**
- **Verifique se existe ao menos uma classe anotada com `@ApplicationBoot` detectável para garantir o funcionamento da aplicação.**
- Use `scanElement = "jar"` para ignorar bibliotecas externas inteiras e `scanElement = "package"` para pacotes locais.

---

## Resumo rápido

| Cenário                            | Recomendações                             |
|----------------------------------|-----------------------------------------|
| Ignorar bibliotecas externas      | `scanElement = "jar"` + `INCREMENT`     |
| Ignorar pacotes locais extras     | `scanElement = "package"` + `INCREMENT`|
| Evitar ignorar pacotes do framework | Nunca usar `REPLACE` para `dtm.di`     |

---

Se tiver dúvidas ou precisar de exemplos mais específicos, consulte a documentação detalhada ou entre em contato com o time de desenvolvimento.

