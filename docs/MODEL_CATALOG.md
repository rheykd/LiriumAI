# Каталог LiriumAI 1.0

Размеры указаны в ГиБ (1024³ байт); в интерфейсе используется подпись ГБ.
ОЗУ — консервативный ориентир разработчика для короткого контекста, не измеренный минимум и не гарантия запуска. Реальные затраты зависят от контекста, vision и свободной памяти системы. Все модели исполняются на CPU; крупные будут медленными.

| Модель | Размер файлов | Фото | ОЗУ, ориентир | Лицензия | Источник |
|---|---:|---|---:|---|---|
| Qwen2.5-VL 3B | 2.58 ГиБ | да | 8 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF) |
| Gemma 3 4B | 3.11 ГиБ | да | 12 ГБ | Gemma Terms of Use | [Hugging Face](https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF) |
| InternVL3 2B | 1.35 ГиБ | да | 6 ГБ | MIT | [Hugging Face](https://huggingface.co/ggml-org/InternVL3-2B-Instruct-GGUF) |
| SmolVLM2 2.2B | 1.59 ГиБ | да | 6 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/ggml-org/SmolVLM2-2.2B-Instruct-GGUF) |
| SmolVLM 500M | 0.51 ГиБ | да | 4 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF) |
| Qwen3 4B | 2.33 ГиБ | нет | 8 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF) |
| Llama 3.2 3B | 1.88 ГиБ | нет | 8 ГБ | Llama 3.2 Community License | [Hugging Face](https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF) |
| Gemma 3 1B | 0.75 ГиБ | нет | 4 ГБ | Gemma Terms of Use | [Hugging Face](https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF) |
| Qwen2.5 0.5B Instruct | 0.46 ГиБ | нет | 4 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) |
| Qwen2.5 1.5B Instruct | 1.04 ГиБ | нет | 6 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) |
| Qwen2.5 7B Instruct | 4.36 ГиБ | нет | 12 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF) |
| Qwen3 8B | 4.68 ГиБ | нет | 12 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/Qwen/Qwen3-8B-GGUF) |
| Qwen3 14B | 8.38 ГиБ | нет | 24 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/Qwen/Qwen3-14B-GGUF) |
| Gemma 3 12B | 7.59 ГиБ | да | 24 ГБ | Gemma Terms of Use | [Hugging Face](https://huggingface.co/ggml-org/gemma-3-12b-it-GGUF) |
| SmolLM3 3B | 1.78 ГиБ | нет | 8 ГБ | Apache-2.0 | [Hugging Face](https://huggingface.co/ggml-org/SmolLM3-3B-GGUF) |

Семь новых моделей: Qwen2.5 0.5B / 1.5B / 7B, Qwen3 8B / 14B, SmolLM3 3B, Gemma 3 12B (с mmproj).
У новых файлов проверены HTTP Range, точный размер, сигнатура GGUF и general.architecture в начале файла. Архитектуры qwen2, qwen3, gemma3, smollm3 присутствуют в вендоренном llama.cpp. Ссылки новых записей закреплены за конкретным commit SHA. Полностью веса не скачивались, генерация на реальном телефоне не проверялась.

Модели свыше текущего фильтра скрыты: в настройках передвиньте ползунок максимального размера до нужного значения (до 10 ГБ). Вкладка скачанных моделей показывает установленные модели независимо от фильтра.
