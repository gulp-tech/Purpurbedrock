# PurpurBedrockNPC 26.2

Сверхлёгкий standalone-плагин неподвижных mob-NPC для Java и Bedrock.

## Почему почти не создаёт нагрузку

- у мобов полностью отключены AI, awareness, гравитация, коллизия, звук, урон, подбор предметов и таблица лута;
- нет ни одной периодической scheduler-задачи;
- голограмма любой длины — одна `TextDisplay`, а не ArmorStand на каждую строку;
- действия выполняются только по клику;
- NPC получают metadata/tag `NPC`, поэтому встроенный оптимизатор Purpurbedrock адаптивно ограничивает их сетевую видимость для Bedrock;
- координаты и действия сохраняются в `plugins/PurpurBedrockNPC/config.yml`.

## Команды

```text
/pbnpc create <id> <mob>
/pbnpc remove <id>
/pbnpc movehere <id>
/pbnpc type <id> <mob>
/pbnpc holo <id> set <MiniMessage текст | новая строка>
/pbnpc holo <id> clear
/pbnpc holoheight <id> <0.5..10>
/pbnpc action <id> add console <команда>
/pbnpc action <id> add player <команда>
/pbnpc action <id> add message <MiniMessage текст>
/pbnpc action <id> add menu <имя DeluxeMenus>
/pbnpc action <id> add teleport here
/pbnpc action <id> add teleport <world;x;y;z;yaw;pitch>
/pbnpc action <id> list
/pbnpc action <id> remove <номер>
/pbnpc action <id> clear
/pbnpc list
/pbnpc info <id>
/pbnpc reload
/pbnpc selftest
```

Плейсхолдеры действий: `%player%`, `%uuid%`, `%npc%`, `%world%`.

Право администратора: `purpurbedrocknpc.admin` (по умолчанию OP).
