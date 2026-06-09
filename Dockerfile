# ---- Этап 1: сборка ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# сначала только pom — чтобы слой с зависимостями кэшировался
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package

# ---- Этап 2: запуск ----
# JRE на базе Ubuntu (glibc), а не Alpine — у нативной библиотеки SQLite
# бывают проблемы с musl, поэтому Alpine специально не используем.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/TheUserOfTheDayBot.jar app.jar

# Каталог /data — сюда кладётся файл базы bot.db (монтируется как том).
VOLUME /data
ENV DB_URL="jdbc:sqlite:/data/bot.db"

ENTRYPOINT ["java", "-jar", "app.jar"]
