# Dark.Talk API и WebSocket

Документ описывает HTTP API аккаунтов и чатов и WebSocket-события, реализованные в `account` и `chat`.

## Базовые URL

- API аккаунтов: `/account/api/`
- API чатов: `/chat/api/`
- WebSocket список/события пользователя: `/ws/chats/`
- WebSocket конкретного чата: `/ws/chat/{chat_id}/`

Пути чувствительны к завершающему `/`: `APPEND_SLASH=False`.
HTTP API принимает JSON; запросы с файлами отправляйте как `multipart/form-data`.
Время в ответах передаётся в формате ISO 8601.

## Авторизация и общие правила

Для HTTP API передавайте заголовки:

```http
Authorization: Token <device-token>
DARK-TALK-SECRET-KEY: <configured-secret>--<application>|<version>
```

Например, часть после `--` имеет формат `web|1.2.3`. Приложение и версия извлекаются из заголовка, но само значение `application|version` сервером не валидируется. Секрет должен быть предоставлен сервером отдельно; не храните его в публичном клиентском коде.

Успешный вход или регистрация возвращает `token`. Токен привязан к устройству, используйте его как `Authorization: Token ...` для запросов от имени пользователя. HTTP permission class проверяет секрет приложения, но сам по себе не требует авторизованного пользователя; клиентским интеграциям следует всегда передавать действительный токен на защищённых пользовательских операциях. Для регистрации, входа и шагов 2FA достаточно секрета приложения; если при этом отправлен некорректный Authorization, DRF может отклонить запрос.

Для WebSocket при token-аутентификации передавайте:

```http
Authorization: Token <device-token>
dark-talk-secret-key: <configured-secret>--<application>|<version>
```

При переданном токене сервер проверяет и токен, и секрет; токен с заблокированного устройства не принимается. Если токен не передан, Channels может аутентифицировать соединение через Django session cookie. WebSocket-подключение к чату разрешается только участнику чата, который не покинул его.

Ошибки HTTP обычно содержат `{"status":"error","message":"ERROR_CODE"}`; некоторые endpoints возвращают `detail` или ошибки полей сериализатора. Проверяйте HTTP-код ответа. WebSocket-ошибки описаны ниже.

## API аккаунтов

Все пути в таблице указаны относительно `/account/api`.

| Метод и путь | Авторизация | Назначение и тело |
|---|---|---|
| `POST /auth/register/` | Секрет приложения | Создать аккаунт и устройство. Тело: `email`, `password`; необязательные `username` (по умолчанию часть email до `@`), `language` (`Russian` или `English`, по умолчанию `Russian`), `date_of_birth`, `device_id`. Устройство и запись истории входа создаются автоматически. Ответ `201`: `status`, `token`, `user`, `device`. |
| `POST /auth/login/` | Секрет приложения | Тело: `username`, `password`, необязательный `device_id`. Обычный успешный вход: `200` с `status`, `token`, `user`, `device`. Если включена 2FA: `202` с `status: "two_factor_required"`, `challenge_id`, `expires_at`; токен до подтверждения не выдаётся. Ограничение входа: 10 запросов в минуту. |
| `POST /auth/2fa/verify/` | Секрет приложения | Тело: `challenge_id`, `code`, необязательный `device_id`. При действительном коде возвращает `200` и те же `token`, `user`, `device`, что обычный вход. Код действует 10 минут и используется один раз. |
| `POST /auth/2fa/resend/` | Секрет приложения | Тело: `challenge_id`. Отмечает прежний challenge использованным и высылает новый; ответ содержит `status: "two_factor_required"`, новый `challenge_id`, `expires_at`. Ограничение для verify/resend: 10 запросов в минуту. |
| `GET /2fa/` | Токен | Возвращает `two_factor_enabled`. |
| `PATCH /2fa/` | Токен | Тело `{"enabled": true}` или `{"enabled": false}`. Значение должно быть boolean; для включения нужен email. Изменение настройки отзывает незавершённые challenges. |
| `POST /auth/logout/` | Токен | Тело: `device_id`. Отзывает токен данного пользователя на данном устройстве. Ответ `204`. |
| `GET /profile/` | Токен | Возвращает `{"status":"success","user": ...}`. |
| `PATCH /profile/` | Токен | Частичное изменение профиля: `username`, `avatar`, `avatar_access`, `info`, `date_of_birth`, `language`. Другие поля отклоняются. `avatar_access`: `all`, `authenticated`, `nobody`. |
| `GET /users/search/?username={query}` | Токен | Поиск активных пользователей по подстроке username без учёта регистра; максимум 20 записей. Ответ: `users` с `id`, `username`, `avatar`. Пустой запрос возвращает `400 USERNAME_NOT_PROVIDED`. |
| `GET /devices/` | Токен | Список устройств текущего пользователя (`devices`). |
| `GET /devices/{device_id}/` | Токен | Информация об устройстве (`device`). В URL ожидается UUID; endpoint ищет устройство текущего пользователя по полю `device_id`. |
| `PATCH /devices/{device_id}/` | Токен | Изменить `name` и/или `trusted`. |
| `DELETE /devices/{device_id}/` | Токен | Удалить своё устройство и его токен; ответ `204`. Чужое устройство возвращает `404`. |
| `GET /login-history/` | Токен | История входов текущего пользователя (`login_history`). |

`user` при регистрации/входе включает `id`, `username`, `email`, `avatar`, `info`, `date_of_birth`, `language`, `is_online`, `last_online`, `email_confirmed`, `two_factor_enabled`, `date_joined`. Для профиля разрешено изменять только явно перечисленные выше поля.

`device` содержит идентификаторы и сведения, собранные сервером из User-Agent, IP и данных приложения: `id`, `user`, `device_id`, `name`, `device_type`, ОС/браузер, приложение и версия, IP, страна/город/часовой пояс, `trusted`, `blocked`, `created_at`, `last_seen`. На регистрации/входе передача `device_id` необязательна: сервер может взять его из `X-Device-ID` или создать новый.

Ошибки авторизации/2FA, на которые полезно реагировать клиенту:

- `INVALID_CREDENTIALS` (`401`), `USERNAME_PASSWORD_NOT_PROVIDED` (`400`);
- `TWO_FACTOR_EMAIL_NOT_CONFIGURED` (`400`);
- `INVALID_OR_EXPIRED_TWO_FACTOR_CODE` или `INVALID_OR_EXPIRED_TWO_FACTOR_CHALLENGE` (`400`);
- заблокированное устройство: `403`, `detail: "Это устройство заблокировано."`;
- неподдерживаемый язык: `LANGUAGE_NOT_SUPPORTED` (`400`).

## API чатов

Все пути в таблице указаны относительно `/chat/api`. Для каждого endpoint требуется токен и секрет приложения.

### Чаты

| Метод и путь | Назначение и тело |
|---|---|
| `GET /chats/` | Список чатов текущего пользователя. Каждый объект содержит `id`, `chat_type`, `title`, `description`, `avatar`, `created_by`, даты, `unread_count`, `last_message_id`, `participants`. Для личного чата `title` — username собеседника. |
| `POST /chats/create/` | Создать чат. Личный: `{"chat_type":"direct","participant_name":"username"}`. Повторный запрос с тем же собеседником возвращает существующий `chat_id` с `200`; новый чат — `201`. Нельзя начать личный чат с собой. Групповой: `{"chat_type":"group","participant_names":"alice,bob","title":"Название","description":"..."}`; `title`, `description`, `avatar` необязательны. Список участников передаётся строкой username через запятую; неизвестные имена пропускаются. `avatar` можно передать multipart-файлом. |
| `GET /chats/{id}/` | Детали чата и участники. Доступен только участнику. |
| `PATCH /chats/{id}/` | Изменить групповой чат (только admin/owner). Поля: `new_participants` и `del_participants` — строки username через запятую; `new_title`, `new_description`, multipart `new_avatar`; `new_role` в формате `"username:admin"` или `"username:member"`. |
| `DELETE /chats/{id}/` | Удалить чат целиком; только admin/owner. Ответ `204`. |
| `POST /chats/{id}/participants/` | Добавить участников; только admin/owner. Тело `{"usernames":["alice","bob"]}` либо `{"username":"alice,bob"}`. Ответ `added` содержит фактически добавленные имена. |
| `DELETE /chats/{id}/participants/{user_id}/` | Участник может выйти сам, кроме владельца; admin/owner может удалить участника, но не владельца. |
| `PATCH /chats/{id}/participants/{user_id}/` | Admin/owner может изменить `is_muted` (boolean). Только owner может также назначить `role: "admin"` или `"member"`. Ответ возвращает `user_id`, `role`, `is_muted`. |
| `POST /chats/blocked/{username}/` | Добавить пользователя в список блокировки текущего пользователя. Самого себя блокировать нельзя. |
| `POST /chats/unblocked/{username}/` | Удалить пользователя из списка блокировки. |

Роли участника: `owner`, `admin`, `member`. Поля профиля участника в чатах сериализуются через публичный сериализатор аккаунта: `id`, `username`, `avatar`, `is_online`, `last_online`, `info`, `date_of_birth`, `language`. Email и настройки безопасности в этом объекте не передаются. `avatar` равен `null`, если он не задан или `avatar_access` установлен в `nobody`; остальные варианты `avatar_access` в текущем публичном сериализаторе не фильтруются.

### Сообщения

| Метод и путь | Назначение и тело |
|---|---|
| `GET /chats/{id}/messages/?limit=50&before_id={id}` | История чата, доступная участникам. `limit` ограничивается диапазоном 1–100 (по умолчанию 50). `before_id` возвращает более старые сообщения с меньшим ID. Ответ: `messages` в хронологическом порядке, `has_more`, `next_before_id`. Некорректная пагинация: `400 INVALID_PAGINATION`. |
| `POST /messages/create/` | Создать сообщение. Тело: `chat_id`; необязательные `text`, `message_type`, `reply_to`, `attachment`, `metadata`, `client_message_id`. Допустимые типы: `text`, `voice_message`, `image`, `file`, `system`. Файл загружайте multipart. Ответ `201`: `chat_id`, `message_id`; полное сообщение доставляется realtime-событием `message_created`. |
| `PATCH /messages/{id}/` | Автор сообщения может изменить `text`. Возвращает `message` целиком. Удалённое сообщение изменить нельзя. |
| `DELETE /messages/{id}/` | Автор сообщения или admin/owner чата может удалить сообщение. Удаление soft-delete: текст очищается, `is_deleted=true`; ответ содержит `message_id`. |
| `POST /messages/read/{id}/` | Отметить конкретное сообщение прочитанным; ответ содержит `message_id`, `read_at`. |
| `POST /chats/{id}/read/` | Отметить входящие сообщения прочитанными, необязательно ограничить до `last_message_id`. Без параметра отмечает до последнего сообщения в чате. Ответ: `chat_id`, `last_read_message_id`. |
| `POST /messages/reaction/{id}/` | Добавить реакцию к сообщению. Тело `{"emoji":"❤️"}`; если emoji не задан, используется `🔥`. Максимум 32 символа. |
| `DELETE /messages/reaction/{id}/?emoji=❤️` | Удалить собственную реакцию. Допускается передать `emoji` в JSON-теле вместо query string. |

Объект сообщения содержит `id`, `chat_id`, `sender`, `reply_to`, `message_type`, `text`, `attachment`, `attachment_name`, `attachment_size`, `metadata`, `is_edited`, `is_deleted`, `created_at`, `updated_at`, `reactions`, `read_by`. У удалённого сообщения `text` и имя вложения пустые, а `attachment` и `attachment_size` равны `null`.

Права проверки членства и доступности чата применяются на HTTP endpoints; попытка обратиться к чужому/недоступному чату обычно возвращает `404`.

## WebSocket

### Подключение

WebSocket endpoint списка событий пользователя:

```text
ws(s)://<host>/ws/chats/
```

Подключение к чату:

```text
ws(s)://<host>/ws/chat/{chat_id}/
```

После успешного подключения сервер сразу отправляет:

```json
{"type":"connection_ready","scope":"chats"}
```

или для чата:

```json
{"type":"connection_ready","chat_id":42}
```

Отклонение по авторизации закрывает соединение с кодом `4001`; нет членства в чате — `4003`. При попытке отправить сообщение в чат после выхода пользователя проверка членства также закрывает соединение кодом `4003`.

`/ws/chats/` получает события всех чатов пользователя, но не typing-события. Подключение к `/ws/chat/{id}/` получает события только этого чата, включая typing.

### Клиентские события в `/ws/chat/{chat_id}/`

Отправляйте JSON-объекты с полем `type`:

| `type` | Поля | Результат |
|---|---|---|
| `send_message` | `text` (непустая строка), необязательные `message_type: "text"`, `reply_to`, `client_message_id` | Создаёт текстовое сообщение и рассылает `message_created`. Вложения через WebSocket не поддерживаются — используйте HTTP `POST /chat/api/messages/create/`. |
| `typing` | `is_typing` (только точное `true` включает индикатор; иначе `false`) | Рассылает `typing` только подписчикам данного чата. Событие не сохраняется. |
| `read_message` | `message_id` | Сохраняет прочтение и обновляет курсор участника; рассылает `message_read`. |
| `edit_message` | `message_id`, непустой `text` | Автор может изменить своё неудалённое сообщение; рассылает `message_updated`. |
| `delete_message` | `message_id` | Автор может удалить своё неудалённое сообщение; рассылает `message_deleted`. В отличие от HTTP DELETE, WS-обработчик не даёт admin/owner удалить чужое сообщение. |

Неподдерживаемый `type` даёт `{"type":"error","code":"unknown_event"}`. Другие коды:

- `invalid_payload` — payload не JSON-объект;
- `empty_text` — пустой текст в `send_message`;
- `unsupported_message_type` — тип в `send_message` не `text`;
- `invalid_reply` — `reply_to` не является сообщением этого чата;
- `edit_forbidden`, `delete_forbidden` — сообщение нельзя изменить/удалить;
- `invalid_message` — недопустимый или отсутствующий `message_id` для чтения.

Формат ошибки может включать пояснение в `message`, например при попытке передать вложение.

### Серверные события

| `type` | Поля события | Где приходит |
|---|---|---|
| `chat_created` | `chat_id`, `chat` (тип, название, описание, аватар, автор, даты, участники) | Подписчикам `/ws/chats/` всех участников нового чата. |
| `message_created` | `chat_id`, `message`, необязательный `client_message_id` | Чат и потоки `/ws/chats/` его участников. Возникает и при создании сообщения через HTTP, и через WebSocket. |
| `message_updated` | `chat_id`, полное `message` | Чат и пользовательские потоки при редактировании через WebSocket. |
| `message_deleted` | `chat_id`, `message_id` | Чат и пользовательские потоки при удалении через WebSocket. |
| `message_read` | `chat_id`, `message_id`, `user_id`, `read_at` | Чат и пользовательские потоки после WS `read_message`. |
| `typing` | `chat_id`, `user_id`, `is_typing` | Только подписчики WebSocket этого чата. |

В объекте `message` WebSocket timestamp-поля представлены строками ISO 8601. Сериализатор сообщения общий по форме с HTTP API: он также содержит sender, reply, вложение, metadata, реакции и список `read_by` (ID пользователей).

HTTP-создание чата публикует `chat_created`; HTTP-создание сообщения публикует `message_created`. HTTP-endpoints редактирования, удаления, прочтения и реакций сами по себе не публикуют соответствующие WebSocket-события. Для изменений через эти HTTP endpoints обновляйте локальное состояние по HTTP-ответу или повторно запрашивайте данные.

## Заметки для интеграции

- Храните токен отдельно для каждого устройства; удаление устройства или logout отзывает соответствующий токен.
- Идемпотентность на сервере явно обеспечена для повторного создания личного чата и повторного добавления одинаковой реакции/блокировки; не полагайтесь на неё для отправки сообщений.
- `client_message_id` возвращается в realtime-событии для клиентской корреляции, но сервер не использует его как ключ дедупликации.
- Список заблокированных пользователей доступен через указанные endpoints; в текущей логике отправки сообщений отдельная проверка этого списка не выполняется.
- Для realtime в production настроен Redis channel layer; серверу нужны доступный Redis и ASGI-процесс с Channels.
