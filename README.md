# Purpurbedrock `26.2`

**Purpurbedrock `26.2`** — автономное высокопроизводительное серверное ядро на базе **Purpur `26.2` (`STABLE`)**, созданное специально для **Minecraft: Bedrock Edition (`26.30–26.52`)** с **100% нативной поддержкой всех Java-плагинов и сундучных Java-меню** — без зависимости от внешних плагинов GeyserMC или облачных API.

## Скачать готовое ядро

- **Готовый JAR (`26.2`)**: [`purpurbedrock-26.2.jar`](https://github.com/gulp-tech/Purpurbedrock/releases/download/v26.2.0-bedrock/purpurbedrock-26.2.jar)
- **Скрипт разблокировки LAN для Windows 10/11**: [`unlock-windows-lan.bat`](https://github.com/gulp-tech/Purpurbedrock/releases/download/v26.2.0-bedrock/unlock-windows-lan.bat) *(также создаётся автоматически при первом запуске ядра)*

---

## Что реализовано внутри ядра Purpurbedrock `26.2`

1. **Стабильная версия `26.2` (`26.2-R0.1-SNAPSHOT`) — работают все плагины**
   - Полная совместимость с `DeluxeMenus`, `packetevents 2.13.0`, `DecentHolograms`, `VoidItems`, `VoidTab`, `AuthMe`, `WorldEdit`, `WorldGuard`, `LuckPerms`, `Vault`, `PlaceholderAPI` и любыми Bukkit/Spigot/Paper/Purpur плагинами.
   - Встроенная защита `SimpleHelpMap` от сбоев `HelpTopicFactory` у кастомных плагинов.

2. **Полная независимость от внешнего GeyserMC и мгновенный вход**
   - Протокольный движок Bedrock `26.2` вшит напрямую в `purpur-server` (никаких папок `plugins/Geyser-Spigot`, никаких обращений к серверам GeyserMC/Mojang/bStats).
   - Языковой кэш встроен прямо в JAR — **первый и последующие запуски проходят мгновенно**, а игроки заходят на сервер **сразу без зависания «Поиск сервера / Загрузка ресурсов»** (`auth-type: offline`, `above-bedrock-nether-building: false`, `forward-player-ping: true`).
   - Прямой вход с Java Edition автоматически заблокирован на уровне `ServerHandshakePacketListenerImpl` / `ServerLoginPacketListenerImpl`.

3. **100% поддержка Java-меню (`9x1`–`9x6`)**
   - Нативная обработка кликов, отмены переноса предметов и мгновенных переходов между подменю прямо в `ServerGamePacketListenerImpl` + `JavaChestMenuManager` (`DeluxeMenus`, `ChestCommands`, любые кастомные `InventoryHolder` GUI открываются у игроков Bedrock как полноценные сундучные меню без фантомных предметов на курсоре).
   - Проверить встроенное Java-меню прямо из коробки: `/purpurbedrock menu` (или `/pb menu`).

4. **Видимость в локальной сети (LAN / вкладка «Миры») на Bedrock `26.40+`**
   - Включён двойной транспорт `transport: both`:
     - **UDP/TCP `19132`** — RakNet + HTTP/WebSocket сигналинг NetherNet
     - **UDP `19133` / `19134`** — WebRTC медиа-канал NetherNet
     - **UDP `7551`** — встроенный **`NetherNetDiscoverySignaling`**, благодаря которому новый интерфейс Bedrock (`Миры`) автоматически находит локальный сервер каждые 2 секунды!

5. **Встроенный Anti-Xray специально под Bedrock (`EngineMode.HIDE`)**
   - Автоматически включён во всех мирах (`BedrockAntiXrayHelper` + дефолты Paper `WorldConfiguration`):
     - **Обычный мир (`Y <= 128`, `lava-obscures: true`)**: скрывает `diamond_ore`, `deepslate_diamond_ore`, `emerald_ore`, `gold_ore`, `iron_ore`, `copper_ore`, `lapis_ore`, `redstone_ore`, `coal_ore` и `raw_iron_block` / `raw_copper_block`, заменяя несоприкасающиеся с воздухом жилы на камень/глубинный сланец.
     - **Незер (`Y <= 128`, `lava-obscures: true`)**: скрывает **`ancient_debris`** (древние обломки / незерит), `nether_gold_ore` и `nether_quartz_ore`, заменяя их на незерак.
   - Режим `HIDE` даёт **0 фейковых руд**, поэтому **не просаживает FPS на слабых телефонах** и практически не нагружает CPU сервера.

6. **Ноль флагов движения (`BedrockMovementGuard`) и ноль фантомных блоков (`BedrockGhostBlockResync`)**
   - Устранены ложные откаты `moved wrongly!` / `moved too quickly!` на лестницах, полублоках, коврах, воде и при спид-бриджинге Bedrock.
   - Повышен порог `PacketLimiter` (`1500 pkt / 7s`), `checkLimit` (`24` блока в бурсте для спид-бриджинга) и `bedrockMaxFloatingTicks` (`200`), а повторные `AcceptTeleportationPacket` по UDP/WebRTC больше не кикают игрока.
   - При любой отмене постановки или ломания блока (`WorldGuard`, спавн-протект, граница дистанции `6.75` блока, застревание `CLIPPED_INTO_BLOCK`) сервер мгновенно отправляет точную синхронизацию блока и всех 6 соседних граней + контрольный ресинк на следующем тике — **фантомных блоков нет вообще**.

7. **Боёвка Bedrock Edition (`1.8` без КД) + высокая оптимизация TPS**
   - Отключён кулдаун удара 1.9 (`attackStrengthTicker = 9999`), таблица урона мечей/топоров как на Bedrock, криты в спринте, сохранение спринта в комбо, мгновенный блок щитом + щит на шифте, откидывание снежками и яйцами.
   - Из коробки включены **Alternate Current Redstone** (в 5–10 раз быстрее ванильного редстоуна) и **`use-alternate-keepalive: true`**.

---

## Быстрый запуск (Рекомендуемые флаги Aikars / G1GC)

Удалите `Geyser-Spigot.jar` из папки `plugins/` (он больше **не нужен**!) и запустите сервер:

```bash
java -Xms4G -Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=100 \
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 \
  -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 \
  -XX:InitiatingHeapOccupancyPercent=15 -jar purpurbedrock-26.2.jar --nogui
```

### Как увидеть локальный сервер на том же ПК с Windows 10 / 11

Windows по умолчанию блокирует локальные UDP-пакеты для UWP/GDK-приложения Minecraft Bedrock (Loopback Isolation), поэтому сервер на этом же компьютере не отображается во вкладке **«Миры»** и не пускает по `127.0.0.1`, пока не снято ограничение.

**Решение в 1 клик:** нажмите правой кнопкой мыши по автоматически созданному файлу **`unlock-windows-lan.bat`** → **«Запуск от имени администратора»** (достаточно сделать **один раз**):

```bat
CheckNetIsolation LoopbackExempt -a -n="Microsoft.MinecraftUWP_8wekyb3d8bbwe"
```

После этого сервер сразу появится во вкладке **«Миры» (LAN)**, а также будет доступен напрямую по `127.0.0.1 : 19132` или локальному IP вашего ПК (`192.168.x.x : 19132`).

---

## Управление и конфигурация

- **Папка `bedrock/`** *(создаётся автоматически в корне сервера)*:
  - **`bedrock/custom_mappings/`** — кастомные `.json` маппинги предметов, блоков, стеклянных панелей и голов (`Geyser-panes-mapping.json`, `Geyser-spheres-mapping.json`, `migrated-custom-skulls.json` и др.)
  - **`bedrock/extensions/`** — расширения Bedrock-движка (`GeyserJavaGlowing`, `VoidAnvilExtension` и др.)
  - **`bedrock/packs/`** — ресурспаки `.mcpack` и `.zip` (`VoidResourcePack.mcpack`, `VoidSpheres.mcpack`, `GeyserEvent-Pack.mcpack` и др.)
  - *Если в сборке осталась старая папка `plugins/Geyser-Spigot/`, при первом запуске ядро автоматически скопирует оттуда все недостающие файлы в `bedrock/`.*
- **`bedrock.yml`** *(в корне сервера)* — порт (`19132`), `webrtc-port` (`19133`), `lan-discovery` (`true`), `data-folder` (`bedrock`), `motd` и `max-players`.
- **`purpur.yml`** (`settings.bedrock.*` и `world-settings.default.gameplay-mechanics.bedrock-combat.*`) — тонкая настройка гейткипера, Anti-Xray, защиты от флагов, защиты от фантомных блоков, Java-меню, боёвки и авто-адаптации чанков/плагинов `26.3 -> 26.2`.
- **Команда `/purpurbedrock` (`/pb`)**:
  - `/pb status` — сводка состояния ядра, TPS, папок `bedrock/`, защиты от флагов/фантомных блоков и протокола
  - `/pb menu` — открыть встроенное интерактивное Java-меню диагностического центра (`27` слотов)
  - `/pb antixray` — проверить статус Bedrock Anti-Xray по всем мирам
