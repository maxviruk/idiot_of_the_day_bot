# Инструкция: что делать дальше

## Шаг 0. Создать бота в Telegram
1. Открой [@BotFather](https://t.me/BotFather), команда `/newbot`.
2. Задай имя и username (должен заканчиваться на `bot`).
3. BotFather пришлёт **токен** вида `123456789:AAE...` — он понадобится дальше.
4. Чтобы бот корректно работал в группах, у того же BotFather:
   `/mybots` → выбрать бота → **Bot Settings → Group Privacy → Turn off**.
   (С включённым privacy бот видит только команды; выключение надёжнее для розыгрышей и реакции на выход людей.)
5. Добавь бота в нужную группу. Для команд `/remove` и `/reset` бот должен видеть
   администраторов — этого достаточно просто присутствия в чате.

---

## Вариант A. Локально / на своём компьютере (для проверки)
Нужен установленный Docker Desktop.
```bash
cp .env.example .env          # вписать BOT_TOKEN и BOT_USERNAME
docker compose up -d --build  # собрать и запустить в фоне
docker compose logs -f        # смотреть логи (Ctrl+C — выйти из логов, бот продолжит работать)
```
Проверь в Телеге: напиши боту `/help`, в группе — `/reg`, потом `/run`.

Остановить / перезапустить:
```bash
docker compose stop
docker compose up -d
```

---

## Вариант B. VPS (например Hetzner) — рекомендуется для постоянной работы
1. Создай сервер (подойдёт самый дешёвый, ~2 ГБ RAM), ОС Ubuntu.
2. Зайди по SSH и установи Docker:
   ```bash
   curl -fsSL https://get.docker.com | sh
   ```
3. Залей проект на сервер — через git:
   ```bash
   git clone https://github.com/ТВОЙ_АККАУНТ/TheUserOfTheDayBot.git
   cd TheUserOfTheDayBot
   ```
   (или скопируй папку через `scp`).
4. Создай `.env` и впиши токен:
   ```bash
   cp .env.example .env
   nano .env
   ```
5. Запусти:
   ```bash
   docker compose up -d --build
   ```
Благодаря `restart: unless-stopped` бот сам поднимется после перезагрузки сервера и после падений.
База `bot.db` лежит в томе `bot-data` и переживает перезапуски и пересборки.

---

## Вариант C. Railway (деплой прямо из GitHub, без своего сервера)
1. Залей проект на GitHub (см. ниже «Как залить на GitHub»).
2. На [railway.app](https://railway.app): **New Project → Deploy from GitHub repo** → выбери репозиторий.
   Railway сам увидит `Dockerfile` и соберёт образ.
3. В разделе **Variables** добавь переменные:
   - `BOT_TOKEN` — токен от BotFather
   - `BOT_USERNAME` — username бота
   - `BOT_TIMEZONE` — например `Europe/Prague`
   - `DB_URL` — `jdbc:sqlite:/data/bot.db`
4. **ВАЖНО:** добавь **Volume** и примонтируй его к пути `/data`.
   Без тома файл `bot.db` сбросится при каждом передеплое, и вся статистика пропадёт.
5. Deploy. Логи — во вкладке Deployments.

---

## Как залить на GitHub
```bash
git init
git add .
git commit -m "TheUserOfTheDayBot v2 (SQLite)"
git branch -M main
git remote add origin https://github.com/ТВОЙ_АККАУНТ/ИМЯ_РЕПО.git
git push -u origin main
```
Файлы `.env`, `config.properties` и `bot.db` уже в `.gitignore` — токен в репозиторий не попадёт.

---

## Обновление бота (после правок кода)
- Docker / VPS:
  ```bash
  git pull
  docker compose up -d --build
  ```
- Railway: просто запушь изменения в GitHub — передеплой произойдёт автоматически.

## Бэкап базы
Вся база — один файл. Скопировать его из тома Docker:
```bash
docker compose cp bot:/data/bot.db ./bot-backup.db
```

## Если что-то не работает
- Смотри логи: `docker compose logs -f` (или вкладка логов в Railway).
- Бот не отвечает в группе → проверь, что выключил Group Privacy у BotFather (шаг 0.4).
- `bot.db` обнуляется на Railway → не примонтирован Volume к `/data` (шаг C.4).
