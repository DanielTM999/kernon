# Boot com `ManagedApplication`

Este conteúdo foi consolidado em
[Comportamento do framework](FRAMEWORK_BEHAVIOR.md#boot-gerenciado).

O ponto de entrada recomendado é `dtm.di.application.startup.ManagedApplication`.
`dtm.di.application.ManagedApplicationStartup` apenas delega para essa classe.

Resumo das regras que costumavam ficar ambíguas neste documento:

- `@ApplicationBoot` exige `value`; a classe informada é o bootable.
- `@OnBoot`, `@OnApplicationFail`, `@LifecycleHook`, `@EnableSchedule` e
  `@DisableAop` devem ser colocados no bootable quando dependem da inspeção direta do boot.
- `doRun(...)` retorna antes do fim do boot.
- `BEFORE_ALL` roda na thread chamadora; as fases seguintes rodam na `BootThread`.
- o disparo do registro do scheduler ocorre em background antes de `@OnBoot` e não é
  aguardado.
- `AFTER_ALL` é tentado no `finally`, inclusive após erro.
- `ON_CLOSE` pertence ao shutdown, não ao boot.

Para exemplos completos, consulte o [README](../README.MD#exemplo-mínimo).
