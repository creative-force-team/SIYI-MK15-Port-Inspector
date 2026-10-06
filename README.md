# SIYI MK15 Port Inspector

Diagnostic Android application for SIYI MK15. The completed C/D research identified the working Datalink UART and verified the exact channels and live values of the physical C and D buttons. The application remains a reusable inspector for further MK15 interface research.

Current version: 1.5.6.

## Repository workflow

This repository is now the primary source. There is no need to exchange source ZIP archives between iterations.

Local Windows directory:

    C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector

First clone:

    cd /d "C:\54\Projects\!0724 - Coating Robot"
    git clone https://github.com/creative-force-team/SIYI-MK15-Port-Inspector.git
    cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"

Update before the next test:

    cd /d "C:\54\Projects\!0724 - Coating Robot\SIYI-MK15-Port-Inspector"
    git pull

## Windows build

Run:

    BUILD_WINDOWS.bat

The launcher and all PowerShell scripts used from Windows are intentionally ASCII-only. This avoids the Windows PowerShell 5.1 UTF-8-without-BOM parsing problem that was seen in the first local run.

The script:

1. creates a unique runs/build_YYYYMMDD_HHMMSS directory before the actual build starts;
2. starts a transcript before loading the main build script;
3. runs the SIYI protocol self-test;
4. builds the debug APK;
5. copies the APK and SHA256 into the run folder when available;
6. stores the result code and a short Git/OS snapshot;
7. commits only that run directory and attempts to push it to origin/main even when the build failed.

The build output is also copied to:

    out\MK15PortInspector-1.5.6-debug.apk

If automatic upload fails because of network, Git authentication, a remote update, or unrelated local changes, the run directory and local diagnostic commit are preserved. Retry with:

    PUBLISH_LAST_RUN.bat

The publishing script never runs a broad git add. It adds only the current runs/<id> directory, so unrelated local source edits are not silently committed.

## Protocol self-test

RUN_PROTOCOL_SELFTEST.bat uses the same diagnostic run/publish mechanism.

## GitHub Actions

Every source/script change on main is validated on a Windows runner:

- Windows PowerShell 5.1 parses all tools/*.ps1 scripts;
- the SIYI protocol self-test is executed;
- the Android project is built with Gradle;
- the debug APK is uploaded as a GitHub Actions artifact.

Commits that contain only runs/** diagnostics do not start a new CI build.

Detailed project notes: 00_README_RU.md, 01_FINDINGS.md, 02_TASK_SCOPE.md, 03_TESTS.md and 04_PROGRESS.md.

Safety: first RC-channel tests must be performed on the bench, not in flight and without an active propulsion system.


## Real MK15 diagnostics

After installing and running the APK, connect the MK15 to the Windows PC with ADB enabled and run:

    COLLECT_MK15.bat

For 15 seconds the collector records Linux input events and Android logcat while you move SA through all three positions. It also captures USB/input/TTY/network state and pulls the app persistent runtime log. The resulting runs/device_... directory is automatically committed and pushed to GitHub when possible.


## Transport Explorer 1.1.0

The application can now select or probe multiple paths: AUTO, USB COM/CP210x, UDP, paired Bluetooth SPP, raw /dev/ttyHS0 when permissions allow it, and passive Android Input.

The Switch Finder workflow is:
1. capture a baseline;
2. move SA to another physical position;
3. press compare;
4. repeat several times.

The app compares RC channel values, Android input state, transport byte streams/counters, and selected Linux system sources. Candidates that change consistently with SA are ranked above noisy background counters.


## Standalone report handoff 1.2.0

The buddy operating the MK15 does not need ADB to hand diagnostics back.

The app now creates a complete ZIP and offers three destinations directly on screen:

- **ZIP → Download**: saves to `Download/MK15PortInspector/` so Android File Explorer can find it; with ADB it can also be pulled from that public path.
- **ZIP → флешка/файл…**: opens Android's system file picker. If a USB flash drive is mounted and exposed by DocumentsUI, select it there.
- **ZIP → thesystem**: POSTs the report to `https://thesystem.pro/?action=siyi_receive`.

The working ZIP is also kept in the app external files directory under `Android/data/com.mk15.portinspector/files/reports/`.

### thesystem upload contract

`multipart/form-data`

File field: `report` with MIME `application/zip`.

Text fields: `report_id`, `app_version`, `package`, `device`, `android`, `transport`, `sa_channel`, `finder_rounds`.

The UI shows the HTTP result and a short server response. Any HTTP 2xx is treated as success.


## Control Finder 1.3.0 — C/D and momentary controls

A real MK15 report showed that the operator was testing the physical **C/D buttons**, not SA, and that all three internal UART nodes (`/dev/ttyHS0`, `ttyHS1`, `ttyHS2`) are readable and writable from the normal APK sandbox. The embedded CP2102 is also visible to Android USB Host.

Version 1.3.0 therefore:
- passively AUTO-connects USB/UDP/UART0/UART1/UART2 after launch;
- does not auto-connect an unrelated paired Bluetooth device;
- adds a one-button **АВТОПОИСК C/D (20 Гц)** mode;
- samples SIYI RC channels at 20 Hz during active C/D research;
- retains per-channel min/max/last/changeCount so a momentary button press is not lost after the button is released;
- ranks high-value RC/Input activity before continuously changing interrupt counters;
- changes the comparison wording from SA-specific to generic control research.

The existing SA mapping display remains available because the robot team may still need the switch later.


## UART/Datalink correction 1.3.1

Four real 1.3.0 reports showed that USB, UDP and UART0/1/2 were opened and commands were transmitted, but every transport still had rxBytes=0 and no valid SIYI frame was received.

The official SIYI SDK documentation says:
- Datalink communication interface is selected in SIYI TX;
- SDK UART is /dev/ttyHS0;
- SDK UART baud is 115200.

Version 1.3.1 therefore:
- configures /dev/ttyHS0 to 115200 raw mode before opening it;
- keeps ttyHS1/2 as experimental/passive sources and no longer sprays active SIYI commands to them in AUTO;
- after an active C/D probe, explicitly warns on screen when no valid SIYI frame was received and asks the operator to check SIYI TX -> Datalink -> Connection;
- stores UART configuration diagnostics and the valid SIYI frame count in reports.


## UART IUCLC discovery 1.3.2

A real 1.3.1 report finally contained UART0 RX: 84 bytes in two chunks. The last captured frame began:

    75 66 02 20 00 0C 00 68 ...

This is a SIYI 0x48 mapping response damaged by the Linux TTY IUCLC option: ASCII uppercase bytes were converted to lowercase:
- 0x55 ('U') -> 0x75 ('u');
- 0x48 ('H') -> 0x68 ('h').

Restoring only those two bytes makes the recorded CRC exactly match (0xBB1D). The live MK15 mapping contained:
- CH10 = type 1 / entity 2 = C;
- CH11 = type 1 / entity 3 = D.

Version 1.3.2 explicitly disables IUCLC on ttyHS0 and also contains a CRC-validated recovery path for already lowercased SIYI frames. This gives us both a source fix and a defensive parser fallback.


## Research goal correction and UART 1.3.3

The current project stops at the research boundary: identify the exact MK15 software path and channel semantics for C/D. A separate future drone/robot control application will consume the resulting specification; it is not part of this repository's current goal.

The 1.3.2 hardware report confirmed the mapping but showed that effective termios still had IUCLC enabled after the Java streams were opened. Version 1.3.3 therefore configures ttyHS0 **after** opening the streams, verifies effective termios again, and starts the RC probe with SIYI's exact documented 4 Hz request before trying 20 Hz.


## Binary-clean UART and isolated 0x42 probe 1.3.4

The 1.3.3 hardware report revealed a second TTY transformation: effective termios still had **ISTRIP enabled**. This clears bit 7 of every incoming byte. The evidence is exact:

- received mapping CRC bytes `35 2D`;
- CRC calculated from the same frame is `B5 2D`;
- `B5 & 0x7F = 35`.

The next received mapping ended with `00 4A`, while its calculated CRC is `80 4A`. Again, bit 7 was stripped exactly.

Version 1.3.4 explicitly disables `istrip` (plus parity/input transformations) after opening ttyHS0.

It also changes the RC experiment:
- mapping is requested **before** RC streaming;
- the 0x42 start command uses SIYI's exact documented sequence=0 request;
- the start frame is sent three times;
- no 0x48 or other SDK command is sent immediately after 0x42 while waiting for channel frames;
- 4 Hz is tried first, 20 Hz only if no channel frames arrive.


## Same-FD UART bridge 1.3.5

The 1.3.4 hardware report proved that path-based `stty -F /dev/ttyHS0` cannot fix this MK15 UART: every new open of the character device restores vendor defaults, so a second `stty -a` still showed IUCLC/ISTRIP/input translations enabled.

Version 1.3.5 changes the ownership model. A long-lived shell process:
1. opens `/dev/ttyHS0` once as fd 3;
2. applies 115200/raw and all binary flags to **that same open descriptor** via `stty ... <&3`;
3. keeps fd 3 open for the complete session;
4. bridges application writes to fd 3 and fd 3 reads back to the Java parser.

This avoids reopening the TTY between configuration and data transfer.

The active C/D sequence was also corrected so AUTO reconnect no longer sends an early 0x42 before the PRE-RC mapping. The central scenario alone controls: mapping -> wait -> three identical 0x42 requests.


## Native UART 1.4.0

The 1.3.5 hardware report exposed two independent shell-bridge problems:
- every UART0 write failed with `EPIPE (Broken pipe)`, so mapping/0x42 never reached ttyHS0 through that bridge;
- the descriptor shown by shell `stty -a` still contained vendor input transformations.

Version 1.4.0 removes shell/stty from the official UART0 path. A small ARM64 JNI library opens `/dev/ttyHS0` directly and configures that exact descriptor with Android/bionic `tcsetattr`:
- input flags = 0;
- output flags = 0;
- local flags = 0;
- 115200 baud;
- 8N1;
- CLOCAL + CREAD;
- no hardware flow control;
- VMIN=1, VTIME=0.

The same native fd is then used for poll/read/write. Reports record the actual termios bitmasks and `binaryClean=true/false`.

BUILD_WINDOWS.bat installs Android NDK 28.2.13676358 automatically once when necessary.


### NDK selection on the Windows workstation

The workstation already has side-by-side NDK versions `27.0.12077973` and `28.2.13676358`. The project now pins `28.2.13676358`; no additional NDK download is needed locally.

If the pinned NDK is absent on another machine, the build script invokes `sdkmanager` with an explicit `--sdk_root`, avoiding the command-line-tools layout problem seen in the failed local run.


## Windows path fix for native build

The project lives under a Windows path containing spaces and `!`:
`C:\54\Projects\!0724 - Coating Robot\...`.

NDK `ndk-build`/GNU Make reported the existing `Android.mk` as an unknown file when that absolute path was passed as `APP_BUILD_SCRIPT`. To avoid this path parser entirely, native UART 1.4.0 is now compiled directly by the NDK LLVM/Clang driver.

The generated ARM64 `libmk15serial.so` is written to the ignored `.native-jniLibs/arm64-v8a/` directory and packaged by Gradle as a prebuilt JNI library. The build script verifies the ELF architecture with `llvm-readelf` when available.


## C/D research completed

Hardware validation with Port Inspector 1.4.0 completed the current research goal.

Verified on the real MK15:
- SIYI Datalink UART: `/dev/ttyHS0`, 115200;
- native termios is binary clean;
- mapping: `CH10=C`, `CH11=D`;
- released value: `1050`;
- pressed value: `1950`;
- live channel stream: `CMD 0x42`, 4 Hz;
- two independent sessions produced 391 live channel frames and 28 labelled C/D actions with no action/channel mismatches.

See `docs/MK15_CD_FINAL_SPEC.md`.

## Developer SDK

Завершённое исследование C/D оформлено также как подключаемый Android-модуль:

```text
mk15-sdk/
```

Готовый артефакт сборки:

```text
out/MK15-CD-SDK-1.0.0.aar
```

SDK включает native UART0, runtime mapping `0x48`, поток каналов `0x42` 4 Гц и события C/D.

Документация:
- `mk15-sdk/README.md`;
- `docs/MK15_CD_SDK_1.0.0.md`;
- `docs/MK15_CD_FINAL_SPEC.md`;
- `mk15-sdk/evidence/MK15_CD_EVIDENCE.json`.

GitHub Actions run `36052471874` подтвердил сборку APK и Release AAR.

## Полный итоговый отчёт

Полная история исследования, методика, промежуточные тупики, аппаратные доказательства, финальная спецификация и передача результата в SDK собраны в:

`docs/MK15_CD_RESEARCH_FULL_REPORT.md`


## Hardware Controls Research 1.5.0

Версия 1.5.0 добавляет отдельный режим **«Исследование органов управления»** для A/B, SA/SB/SC, LD/RD, дополнительных кнопок/регуляторов и контрольных C/D.

Режим 1.5.0 использует доказанный SIYI UART0 `/dev/ttyHS0`, читает runtime mapping `0x48`, включает диагностический поток `0x42` 20 Гц и записывает отдельные физические опыты без изменения mapping или RC-конфигурации.

Во время опыта таблица каналов показывает entity type/id, Current/Min/Max/Delta, состояние, число и время изменений; недавно изменившиеся строки подсвечиваются.

ZIP-отчёт дополнен:

- `SUMMARY.md`;
- `controls.json`;
- `controls.csv`;
- `events.csv`;
- `mapping_raw.txt`.

Инструкция оператору: `docs/HARDWARE_CONTROLS_RESEARCH_1.5.0.md`.

Назначение HOLD/RTH/MANUAL в рамках этой версии не выполняется: сначала собираются фактические аппаратные измерения.


## Operator interface 1.5.2

Версия 1.5.2 исправляет компоновку режима исследования на реальном экране SIYI MK15.

Причина дефекта 1.5.0/1.5.1 была подтверждена на устройстве: служебные транспортные и диагностические элементы занимали рабочую высоту, поэтому строка выбора и кнопки записи уходили ниже видимой области. Версия 1.5.2 разделяет приложение на три верхних раздела:

- **Исследование** — выбор органа, «1. Подготовить», «2. Записать», «3. Завершить», «Стоп», состояние и прокручиваемая таблица каналов;
- **Отчёт** — сохранение ZIP, системный выбор файла/флешки и отправка отчёта в thesystem;
- **Диагностика** — транспорт, прежний C/D Finder, сведения о портах и журнал.

Основные кнопки имеют минимальную высоту 48 dp. На реальном MK15 подтверждены версия 1.5.2, установленный APK, активное окно и расположение всех четырёх основных кнопок и трёх переключателей разделов внутри экрана 1920×1080.

Подробности и границы доказанности: `docs/MK15_UI_DEBUG_2026-10-05.md`.

Важно: геометрия начального экрана подтверждена; полный интерактивный проход «исследование → запись → ZIP → отправка» должен быть завершён отдельно. До этого повторную физическую серию оператору проводить не требуется.


## UI responsiveness fix 1.5.3

Аппаратная проверка 1.5.2 на реальном SIYI MK15 подтвердила корректный приём RC 20 Гц, запись опыта и формирование ZIP, но выявила избыточную нагрузку интерфейса: каждая рамка 0x42 инициировала перерисовку всех 16 строк каналов.

Версия 1.5.3 сохраняет обработку и запись **каждого** RC-кадра на 20 Гц, но ограничивает визуальную перерисовку таблицы примерно 5 Гц после первых кадров. Это не уменьшает точность исследования; изменение разгружает главный поток Android, чтобы кнопки, переходы и завершение отчёта не ждали длинную очередь обновлений интерфейса.

Причина обнаружена в полном программном цикле на реальном пульте после подтверждённых этапов: UART0 mapping 0x48, поток 0x42 20 Гц, BEGIN/END контрольного опыта C и HCR 0x42 OFF. Сформированный 1.5.2 ZIP был отдельно извлечён и успешно проверен по JSON, CSV, Markdown, mapping и runtime-журналу.


## UI responsiveness fix 1.5.4

Аппаратная проверка 1.5.3 на реальном MK15 показала, что сохранения всех RC-кадров на 20 Гц и визуальной перерисовки таблицы примерно на 5 Гц всё ещё недостаточно для слабого Android-пульта: во время активного потока процесс занимал около половины одного ядра, а Android UI Automation не мог устойчиво получить дерево интерфейса. Сразу после «Стоп» дерево снова становилось доступно.

Версия 1.5.4 **не снижает частоту приёма и записи данных**: Hardware Controls Research по-прежнему получает каждый кадр RC 20 Гц. Ограничена только визуальная перерисовка 16 строк таблицы — после короткой стартовой серии экран обновляется примерно 1 раз в секунду. Для оператора этого достаточно, а точность измерения остаётся прежней.


## Diagnostic logging fix 1.5.5

Проверка 1.5.4 показала, что снижение визуального обновления таблицы до 1 Гц почти не изменило нагрузку: активный процесс на реальном MK15 оставался около 43% одного ядра, а Android UI Automation не мог получить дерево экрана до остановки RC-потока.

Анализ выявил диагностический путь, работавший на каждом 20-Гц кадре UART0 с IUCLC: сообщение о сигнатуре `75 66`, отдельное логирование каждого восстановленного кадра, формирование `lastHex` и частое обновление текстового журнала. В 1.5.5 эти операции разрежены, но сам Hardware Controls Research всё ещё использовал диагностические 20 Гц.

## Safe research stream 1.5.6

После физической серии Сергея 6 октября 2026 года выяснилось, что 20-Гц поток `0x42` способен пережить перезапуск приложения и создавать тяжёлую нагрузку на Android 9 MK15. Версия 1.5.6 устраняет этот риск:

- Hardware Controls Research использует только документированные и уже аппаратно доказанные **4 Гц**;
- автоматическое повышение Finder до 20 Гц удалено;
- интерфейс перерисовывает таблицу не чаще одного раза в секунду независимо от входной частоты;
- команда `0x42 OFF` проверяется по фактическому прекращению UART0-кадров;
- если OFF не подтверждается, приложение повторяет остановку и в крайнем случае принудительно ограничивает остаточный поток до 4 Гц;
- при старте приложение обнаруживает унаследованный поток предыдущего сеанса и пытается погасить его до начала нового исследования.

Аппаратная регрессия на реальном MK15: GitHub Actions run `37511688514`, job `112434177408`.

Результат:

- `RATE_SAMPLE=1800ms sample frames=8 target=4Hz`;
- запросов 20 Гц в исследовательском сценарии нет;
- `RC stop [research]: остановка подтверждена; новых UART0 0x42 кадров=1`;
- `STOP_RESULT=FULL_STOP`;
- `MK15_156_QA2=PASS`.

Установленный APK 1.5.6 / versionCode 56: SHA-256 `ae0aebb07529a680f507fce2472d45bd517a8ab38135dcdc905ed05241a2d306`.


## Финальный статус 2026-10-06

Практическая задача C/D завершена на 100%.

- C → CH10 → 1050/1950;
- D → CH11 → 1050/1950;
- результат повторно подтверждён физической серией Сергея;
- SDK C/D 1.0.0 остаётся актуальным;
- Port Inspector 1.5.6 установлен и аппаратно проверен на 4 Гц;
- расширенный инвентарь остальных органов прекращён как ненужный текущей задаче и не выдаётся за завершённый.

Краткий канонический итог:

`docs/MK15_FINAL_STATUS_2026-10-06.md`
