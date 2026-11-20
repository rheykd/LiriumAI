# Сторонние компоненты

Лицензия MIT в корне применяется к собственному коду LiriumAI. Сторонние
компоненты сохраняют свои лицензии.

- **llama.cpp / ggml / mtmd**: [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp),
  MIT. Зависимость закреплена Git-подмодулем; лицензии находятся внутри
  `third_party/llama.cpp/LICENSE` и каталогов `licenses` / `vendor`.
- **Montserrat**: SIL Open Font License 1.1,
  текст в `app/src/main/assets/licenses/montserrat.txt`.
- **AA Strejen**: Vasily Shishkin, разрешено личное и коммерческое использование;
  уведомление в `app/src/main/assets/licenses/strejen.txt`.
- AndroidX, Kotlin и остальные зависимости подключаются через Gradle и сохраняют
  лицензии своих авторов.
- Веса моделей не входят в исходники или APK. Они скачиваются отдельно и
  распространяются по лицензиям, указанным их авторами в карточках моделей.

Изображение иконки предоставлено автором проекта отдельно; лицензия кода не
заменяет права на сторонние изображения и товарные знаки.

- **Vosk Android** (`com.alphacephei:vosk-android:0.3.75`): Apache License 2.0,
  [исходники](https://github.com/alphacep/vosk-api), текст лицензии —
  `app/src/main/assets/licenses/vosk-apache-2.0.txt`.
- **JNA** (`net.java.dev.jna:jna:5.18.1`): двойная лицензия LGPL 2.1 / Apache 2.0;
  используется вариант Apache 2.0, [исходники и лицензия](https://github.com/java-native-access/jna).
- Скачиваемые **Vosk small RU 0.22 / EN-US 0.15** — Apache 2.0;
  [карточки моделей](https://alphacephei.com/vosk/models). В APK веса не включены.
- Системное распознавание и синтез Android предоставляются установленным на
  телефоне сервисом. Его голоса и условия обработки не являются частью LiriumAI.
