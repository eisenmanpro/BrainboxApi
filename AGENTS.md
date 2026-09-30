# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Build & Run
- **Build**: `./gradlew build` (add `-x test` to skip tests)
- **Tests**: `./gradlew test`
- **Run locally**: `./gradlew bootRun`
- **Infra**: `docker compose up -d` (Postgres 17, plus optional Redis 7 — the app runs with `app.redis.enabled=false`)
- **Toolchain**: Java 21 (Gradle toolchain), Gradle wrapper 9.7.1
- **Serialize JVM builds**: Never run Android and API Gradle builds concurrently on this machine — one Gradle invocation at a time. Shared CPU and a slow link make concurrent builds dramatically slower. Web (`npm test`) is light and fine to overlap.
