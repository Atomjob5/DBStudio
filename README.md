# DBStudio

DBStudio is a local, browser-based SQL editor. The first release targets MySQL 8.0 and 8.4. Database-specific behavior is isolated behind the `database-spi` module so an OceanBase provider can be added without changing the editor workbench.

## Architecture

- `database-spi`: database-neutral contracts and domain types
- `database-mysql`: MySQL Connector/J provider, dialect and metadata adapters
- `application-core`: connections, editor sessions, queries, transactions, SQLite, CSV and credential storage
- `editor-web`: Vue 3, TypeScript, Element Plus, Pinia and Monaco application
- `server-app`: Spring Boot 2.7.18 local REST/WebSocket server and executable JAR

The server listens only on a random `127.0.0.1` port. It starts the system browser with a one-time 256-bit token, exchanges that token for an HttpOnly `SameSite=Strict` cookie and then removes the token from the address bar. The frontend is embedded in the executable JAR; Node.js is not required at runtime.

## Build

Build prerequisites:

- JDK 8 or later
- Node.js 20.19 or later with npm
- Docker only when running MySQL integration tests

Build and verify all modules:

```shell
./mvnw clean verify
```

The build compiles Java with `source/target=1.8`, checks Java 8 API usage with Animal Sniffer, runs Java and Vue tests, builds the frontend and creates the executable JAR at `server-app/target/dbstudio-server.jar`.

## Run

The target machine only needs a Java 8 or newer JRE:

```shell
java -jar server-app/target/dbstudio-server.jar
```

DBStudio selects an available local port and opens the authenticated URL in the default browser. Use **More → Exit DBStudio** to roll back open transactions, close sessions and stop the local service. Closing the browser leaves a 60-second reconnect window before its workspace is cleaned up.

Application data is stored under the platform user-data directory. Passwords are not written to SQLite or browser storage; remembered credentials use macOS Keychain or Windows Credential Manager.

## Frontend development

The Maven build runs `npm ci`, `npm run build` and `npm run test` automatically. For standalone UI work:

```shell
cd editor-web
npm ci
npm run dev
```

Open `http://127.0.0.1:5173/?mock=1` to use the development-only mock transport. Production builds ignore the mock flag. Chrome is the primary browser and can open/save SQL files through the File System Access API; other browsers fall back to upload and download.

## Tests

Unit and server security tests do not require Docker. MySQL integration tests use fixed Testcontainers images `mysql:8.0.46` and `mysql:8.4.9` and cover connections, DDL/DML, transactions, metadata, procedures and CSV behavior.

The Element Plus component policy is documented in [docs/element-plus-component-map.md](docs/element-plus-component-map.md). Theme, material and accessibility rules are documented in [docs/apple-ui-style.md](docs/apple-ui-style.md).
