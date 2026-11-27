# API-тесты СМП - Practice 5

## 1. Покрытие

| Эндпоинт | Проверенные коды                            | Где проверяется |
|---|---------------------------------------------|---|
| `GET /health` | `200`; `503` - evidence 04                  | `HealthApiTest` |
| `POST /api/cards` | `201`, `400`, `404`; `503` - evidence 01    | `CardsApiTest` |
| `POST /api/cards/generate` | `201`, `400`, `404`                         | `CardsApiTest` |
| `GET /api/cards` | `200`, `400`, `413`                         | `CardsApiTest` |
| `GET /api/cards/{pan}` | `200`, `400`, `404`                         | `CardsApiTest` |
| `PATCH /api/cards/{pan}` | `200`, `400`, `404`                         | `CardsApiTest` |
| `DELETE /api/cards/{pan}` | `204`, `400`, `404`                         | `CardsApiTest` |
| `POST /api/cards/{pan}/reserve` | `200`, `400`, `402`, `404`, `409`           | `CardsApiTest` |
| `POST /api/cards/{pan}/rollback` | `200`, `400`, `404`, `409`                  | `CardsApiTest` |
| `POST /api/cards/bulk-update` | `200`, `400`                                | `CardsApiTest` |
| `POST /api/transactions` | `200` approve/decline, `400`                | `TransactionsApiTest` |
| `GET /api/transactions/search` | `200`, `400`                                | `QueryApiTest` |
| `GET /api/transactions/export` | `200`, `400`                                | `QueryApiTest` |
| `GET /api/dashboard/stats` | `200`                                       | `QueryApiTest` |
| `GET /api/dashboard/recent` | `200`, `400`                                | `QueryApiTest` |
| `GET /api/dashboard/charts` | `200`, `400`                                | `QueryApiTest` |
| `POST /api/simulator/terminal/run` | `200`, `400`                                | `SimulatorsApiTest` |
| `POST /api/simulator/terminal/start-continuous` | `200`, `400`                                | `SimulatorsApiTest` |
| `POST /api/simulator/terminal/stop` | `200`                                       | `SimulatorsApiTest` |
| `POST /api/simulator/merchant/run` | `200`, `400`                                | `SimulatorsApiTest` |
| `POST /api/simulator/merchant/fee` | `200`, `400`, `404`                         | `SimulatorsApiTest` |
| `GET /api/simulator/merchants` | `200` напрямую; Gateway маршрут отсутствует | `SimulatorsApiTest` |
| `POST /api/internal/authorize` | `200`, `400`, `403`, `404`, `405`, `422`    | `AuthorizationApiTest` |
| `POST /api/internal/rollback` | `200`, `400`, `404`, `409`                  | `AuthorizationApiTest` |
| `POST /api/internal/route` | `200`                                       | `SwitchApiTest` |
| `POST /api/internal/log` | `200`, `201`, `400`, `405`, `409`           | `LoggerApiTest` |

### 2. Бизнес-отказы

| Исход | Код | Причина | Путь достижения |
|---|---:|---|---|
| Карта не найдена | `14` | `CARD_NOT_FOUND` | неизвестный BIN, отсутствующий PAN |
| Карта заблокирована | `05` | `CARD_BLOCKED` | `status=BLOCKED` |
| Карта неактивна | `05` | `CARD_INACTIVE` | `status=INACTIVE` |
| Карта просрочена | `54` | `CARD_EXPIRED` | `status=EXPIRED` и дата запроса после expiry |
| Недостаточно средств | `51` | `INSUFFICIENT_FUNDS` | баланс меньше суммы |
| Превышен лимит | `61` | `EXCEEDS_AMOUNT_LIMIT` | дневной и месячный лимиты |
| Сервис недоступен | `96` | `SERVICE_UNAVAILABLE` | остановка Card Management |
| Резерв не выполнен | `96` | `RESERVATION_FAILED` | отказ reserve в Card Management |
| БД недоступна | `96` | `DB_UNAVAILABLE` | отказ `limit_usage` |
| Неизвестная причина | `96` | `UNKNOWN_REASON` | внутренняя ошибка без отдельной ветки |
| Транзакция не найдена | `14` | `TRANSACTION_NOT_FOUND` | rollback с неизвестным RRN |
| Повторный rollback | `05` | `ALREADY_ROLLED_BACK` | rollback уже отменённой операции |
| Ошибка rollback | `96` | `ROLLBACK_FAILED` | отказ Card Management |

### 3. Валидация

| Объект | Что проверено                                                                                                                         |
|---|---------------------------------------------------------------------------------------------------------------------------------------|
| `POST /api/transactions` | 11 обязательных полей, mti, PAN, amouny, currencyCode, mcc, malformed JSON                                                            |
| `POST /api/cards` | BIN, имя держателя, валюта, дневной и месячный лимиты                                                                                 |
| `POST /api/cards/{pan}/reserve` | amount, RRN: длина и цифры                                                                                                            |
| `POST /api/cards/{pan}/rollback` | amount, RRN, PAN                                                                                                                      |
| `PATCH /api/cards/{pan}` | отрицательный лимит, несуществующая карта                                                                                             |
| `POST /api/cards/generate` | `count=0`, `count > max`, неизвестный BIN                                                                                             |
| `POST /api/cards/bulk-update` | одновременно `bins` и `pans`, отсутствие обоих                                                                                        |
| `GET /api/cards` | `bin`, `status`, `issuerId`, `startDate`, `endDate`, `limit`, `offset`, пустая выборка                                                |
| `GET /api/transactions/search` | `pan`, `status`, `merchantId`, `mcc`, `issuerId`, `dateFrom/dateTo`, `declineReason`, `limit`, `offset`, пустая выборка, обратный диапазон |
| `GET /api/transactions/export` | `pan`, невалидный `limit`, CSV и заголовки                                                                                            |
| `GET /api/dashboard/recent` | валидный и невалидный `limit`                                                                                                         |
| `GET /api/dashboard/charts` | `from/to`, обратный диапазон                                                                                                          |
| Симуляторы | `count`, `tps`, `scenario`, обязательные поля fee                                                                                     |
| `POST /api/internal/log` | обязательные поля, PAN, malformed JSON, метод                                                                                         |

## 4. Обоснование достаточности

1. **Состояния карты:** активная, заблокированная, неактивная, просроченная, отсутствующая.
2. **Лимиты и суммы:** сумма в пределах баланса, больше баланса, больше дневного лимита, больше месячного лимита, нулевая и отрицательная сумма.
3. **Данные запроса:** обязательные и необязательные поля, корректные и некорректные форматы, malformed JSON.
4. **Повторная обработка:** первая запись, повтор с тем же телом, повтор с изменённым `createdAt`, конфликт данных.
5. **Фильтры:** каждый параметр по отдельности, пагинация, пустая выборка, некорректная комбинация.
6. **Симуляторы:** валидный запуск, границы `count/tps`, неизвестный сценарий, справочник и fee.

Почему достаточно:

- для каждого автоматизируемого эндпоинта проверены успешный сценарий и основные классы ошибок;
- бизнес-отказы проверяются совместно с кодом и `declineReason`;
- для expiry и лимитов проверены разные пути достижения;
- повторная доставка проверена по всем стабильным исходам;
- async-эффект проверен и на появление, и на отсутствие

## 5. Выявленные расхождения

| Декларировано                             | Наблюдается                          | Источник декларации     | Классификация |
|-------------------------------------------|--------------------------------------|-------------------------|---|
| `mti=0110` в ответе                       | `0100` - значение из запроса         | `docs/api-spec.md`      | дефект контракта |
| `expiryDate` в формате `MMyy`             | `yyyy-MM`                            | `docs/api-spec.md`      | дефект контракта |
| Понятное сообщение при `dateFrom > dateTo` | пустой `message`                     | `docs/api-spec.md`      | дефект валидации |
| terminal /start, /status в документации   | ревлизуется /start-continuous, /stop | `docs/api/openapi.yaml` | дефект контракта |
| `POST /api/simulator/terminal/run`: обязательны только `count` и `scenario` | без `tps` возвращается `400`: `tps must be >= 1`; фактически `tps` обязателен | `docs/api/openapi.yaml` | дефект контракта |

## 6. Доказательства работоспособности проверок

| № | Тест | Внесённое изменение | Результат |
|---|---|---|---|
| 1 | `TransactionsApiTest#shouldApproveStoreAndDecreaseBalance` | ожидание `mti` изменено на `0110` | ожидался `0110`, фактически `0100`|
| 3 | `GET /health`, `POST /api/internal/authorize` | остановлен `card-management` | `503`, код `96`, `SERVICE_UNAVAILABLE` |
| 4 | `POST /api/simulator/terminal/run` без `tps` | ручная проверка новых расхождений | `400` |


## 7. Порядок запуска

```powershell
.\scripts\api-test.ps1
```

```bash
mvn -B -f services/pom.xml -Papi-tests test
```
