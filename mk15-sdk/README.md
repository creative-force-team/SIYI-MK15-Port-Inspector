# MK15 C/D SDK 1.0.0

Подключаемая Android-библиотека, фиксирующая аппаратно подтверждённый способ чтения физических кнопок C и D на SIYI MK15.

Библиотека открывает `/dev/ttyHS0` нативно, использует 115200 8N1, читает runtime mapping через SIYI `0x48`, включает живой поток каналов через `0x42` 4 Гц и выдаёт события C/D.

Аппаратно подтверждено:

```text
C = CH10
D = CH11
released = 1050
pressed  = 1950
```

Пороги SDK:

```text
<= 1300 -> RELEASED
>= 1700 -> PRESSED
иначе   -> INTERMEDIATE
```

Подключение из этого репозитория:

```gradle
dependencies {
    implementation project(':mk15-sdk')
}
```

Готовый AAR после сборки:

```text
out/MK15-CD-SDK-1.0.0.aar
```

Для внешнего проекта:

```gradle
dependencies {
    implementation files('libs/MK15-CD-SDK-1.0.0.aar')
}
```

AAR содержит ARM64 JNI-библиотеку; NDK приложению-потребителю не требуется.

Mapping MK15 настраиваемый, поэтому рабочий код читает `0x48` и ищет C/D по physical type/entity id. CH10/CH11 в `Mk15Evidence` — свидетельство конкретного исследования, а не замена runtime mapping.

Свидетельства:
- `docs/MK15_CD_FINAL_SPEC.md`
- `mk15-sdk/evidence/MK15_CD_EVIDENCE.json`
- `Mk15Evidence.java`
- `host-tests/Mk15SdkSelfTest.java`


## Повторная аппаратная проверка 2026-10-06

Независимая физическая серия на том же MK15 повторно подтвердила:

```text
C = CH10 = 1050/1950, 6 полных циклов
D = CH11 = 1050/1950, 9 полных циклов
```

Port Inspector 1.5.6 также аппаратно подтвердил именно используемый SDK режим `0x42` **4 Гц**:

```text
RATE_SAMPLE=1800ms sample frames=8 target=4Hz
RESEARCH_4HZ=PASS
STOP_RESULT=FULL_STOP
MK15_156_QA2=PASS
```

Повторная проверка не потребовала изменений API или констант SDK 1.0.0.
