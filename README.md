# Purpurbedrock 26.3

Кастомное серверное ядро на базе **Purpur 26.3** (Paperweight / Paper `26.3`), специально созданное для серверов **Minecraft: Bedrock Edition** с полной поддержкой **Java-плагинов** (Bukkit / Spigot / Paper / Purpur), **боёвкой Bedrock Edition** и **сундучными менюшками как на Java Edition**.

## Готовое ядро (Скачать)

Готовый к запуску однофайловый JAR-архив (`purpurbedrock-26.3.jar`, со встроенным мостом GeyserMC 26.3 внутри самого ядра) публикуется автоматически в разделе **[Releases](../../releases/tag/v26.3.0-bedrock)**:

- **`purpurbedrock-26.3.jar`** — готовое серверное ядро (просто запустите — дополнительные мосты в `plugins/` ставить не нужно).

---

## Что реализовано в ядре

### 1. Вход только с Bedrock Edition (вход с Java Edition закрыт)
- Внутри `purpurbedrock-26.3.jar` уже встроен актуальный мост **GeyserMC 26.3** (`META-INF/purpurbedrock/Geyser-Spigot.jar`), который автоматически поднимает **UDP-порт `19132`** при старте сервера (поддерживаются клиенты Bedrock `1.21.100` – `26.52`).
- На уровне сетевого рукопожатия (`ServerHandshakePacketListenerImpl` и `ServerLoginPacketListenerImpl`) работает фильтр **`BedrockGatekeeper`**:
  - Игроки **Bedrock Edition** (подключающиеся через встроенный `LocalChannel` GeyserMC или внешний Floodgate) свободно заходят на сервер, включая Xbox-никнеймы с пробелами/префиксами.
  - Любые прямые подключения с **Minecraft: Java Edition** по TCP мгновенно отклоняются на этапе логина с настраиваемым сообщением `settings.bedrock.java-kick-message` в `purpur.yml`.

### 2. Настоящая боёвка Bedrock Edition (`BedrockCombatHelper`)
Включена по умолчанию во всех мирах (`gameplay-mechanics.bedrock-combat.*` в `purpur.yml`):
- **Отсутствие КД удара 1.9 (`attackStrengthScale = 1.0`)**: каждый удар наносит 100% урона без шкалы перезарядки.
- **Таблица урона оружия как на Bedrock Edition**: мечи наносят на `+1` урон больше, чем в Java (алмазный меч `8`, незеритовый `9`, железный `7`, каменный `6`, деревянный/золотой `5`), топоры и лопаты пересчитаны по таблице Bedrock Edition. Кастомные атрибуты урона от Java-плагинов (`ATTRIBUTE_MODIFIERS`) полностью сохраняются.
- **Критические удары в спринте**: в отличие от Java 1.9+, криты проходят даже во время бега при падении.
- **Сохранение спринта и комбо**: удар по цели не сбрасывает спринт атакующего (`preserve-sprint-on-attack: true`) и настраиваемо регулирует замедление/отбрасывание.
- **Отключён сплэш-удар мечом (Sweep AoE)**: мечи бьют строго выбранную цель, как на Bedrock Edition (`allow-sweep-attack: false`).
- **Щиты как на Bedrock Edition**:
  - Мгновенный блок (`0` тиков задержки вместо `5` тиков на Java).
  - Автоматический блок щитом при приседании (**Sneak / Shift**), если щит в основной или второй руке и не на кулдауне от топора.
- **Снежки и яйца откидывают игроков**: в PvP снежки и яйца наносят полноценное отбрасывание игрокам (в ванильной Java урон `0` пропускается для игроков).

### 3. Полная поддержка Java-плагинов
- Ядро на 100% совместимо с API **Bukkit, Spigot, Paper и Purpur 26.3** (`ServerBuildInfo` сохраняет совместимость с брендами `papermc:paper` и `purpurmc:purpur`).
- Просто закидывайте любые стандартные `.jar` плагины Java (`LuckPerms`, `Vault`, `EssentialsX`, `PlaceholderAPI`, `WorldGuard`, `DeluxeMenus`, `Citizens` и др.) в папку **`plugins/`**.

### 4. Менюшки как на Java Edition (инвентарные Chest GUI вместо форм Bedrock)
- Все меню отображаются у игроков Bedrock как **настоящие сундучные инвентари Java** (`9x1`..`9x6` слотов, наковальни, воронки и т.д.), а не диалоговые формы Bedrock (`FormWindow`).
- **Анти-фантомная синхронизация кликов (`instant-resync-on-cancel: true`)**: при отмене `InventoryClickEvent` любым плагином (или встроенным меню) ядро мгновенно отправляет полную синхронизацию контейнера и курсора (`sendAllDataToRemote()`), полностью устраняя визуальные баги перетаскивания предметов у клиентов Bedrock.
- **Встроенный движок меню `menus.yml` (`JavaChestMenuManager`)**: при первом запуске в корне сервера создаётся готовый файл `menus.yml` (в формате, знакомом по `DeluxeMenus` — с поддержкой `open_command`, `size`, `items`, `slot`/`slots`, `left_click_commands` / `click_commands`, `[player]`, `[console]`, `[openmenu]`, `[message]`, `[sound]`, `[close]`, цветов MiniMessage и `&`, а также `PlaceholderAPI`). По умолчанию уже доступна команда `/menu`, а если вы установите свой плагин меню (например, `DeluxeMenus`), встроенная команда автоматически уступит приоритет плагину.

---

## Быстрый старт

1. Скачайте **`purpurbedrock-26.3.jar`** в отдельную папку сервера.
2. Примите EULA и запустите сервер на **Java 25**:
   ```bash
   echo "eula=true" > eula.txt
   java -Xms4G -Xmx4G -jar purpurbedrock-26.3.jar --nogui
   ```
3. Откройте порт **`19132/UDP`** на хостинге/фаерволе — игроки **Minecraft: Bedrock Edition** заходят напрямую по вашему IP и порту `19132`!
4. В `server.properties` при использовании встроенного автономного входа Bedrock установите `online-mode=false` (авторизация Xbox Live при этом проверяется самим встроенным мостом GeyserMC: `validate-bedrock-login: true` в `plugins/Geyser-Spigot/config.yml`).
5. Загружайте любые Java-плагины в папку `plugins/` и настраивайте сундучные меню в `menus.yml` (перезагрузка без рестарта: `/purpurbedrock reload`).

---

## Настройки в `purpur.yml`

```yaml
settings:
  bedrock:
    only-allow-bedrock: true
    allow-external-floodgate: true
    java-kick-message: "<red><bold>Заход с Minecraft: Java Edition отключён!</bold>\n<gray>Этот сервер доступен только для игроков <aqua>Minecraft: Bedrock Edition</aqua>."
    embedded-geyser:
      enabled: true
      port: 19132
    menus:
      instant-resync-on-cancel: true
      built-in-menus-enabled: true
      file: "menus.yml"

world-settings:
  default:
    gameplay-mechanics:
      bedrock-combat:
        enabled: true
        use-bedrock-weapon-damage: true
        allow-sprint-crits: true
        allow-sweep-attack: false
        preserve-sprint-on-attack: true
        attacker-sprint-slowdown: 0.85
        knockback-multiplier: 1.0
        instant-shield-block: true
        sneak-shield-blocking: true
        projectiles-knockback-players: true
```

## Команды администратора

- `/purpurbedrock status` — проверить статус фильтра только-Bedrock, встроенного моста GeyserMC, боёвки Bedrock и загруженных Java-меню.
- `/purpurbedrock reload` — перезагрузить `menus.yml` без перезапуска сервера.
- `/purpurbedrock openmenu <id> [игрок]` — открыть любое сундучное Java-меню из `menus.yml` игроку.

---

## Сборка из исходников

Требуется **JDK 25**:
```bash
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```
