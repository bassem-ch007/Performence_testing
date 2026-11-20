# E-commerce performance test: monolith vs microservices

The same e-commerce backend (users, products, carts, orders) is implemented twice:

| Architecture | Modules | Communication |
|---|---|---|
| **Monolith** | `ecommerceMono` | in-process method calls |
| **Microservices** | `user-service`, `product-service`, `order-service` | REST for clients, **gRPC** between services |

Both versions expose the **same REST API**, run in Docker with the **same resource budget**, and are
observed with the same monitoring stack (Prometheus, Grafana, Loki, Fluent Bit, cAdvisor, node-exporter),
so they can be load-tested with Apache JMeter and compared.

```
                         Monolith profile                         Microservices profile
                 ┌────────────────────────────┐        ┌──────────────┐ gRPC ┌──────────────┐
  JMeter ──HTTP──▶   ecommerce-mono :8080     │        │ order-service├─────▶│ user-service │ :8081 / :9091
                 │ users·products·carts·orders│        │ :8083 / :9093├─────▶│product-service│ :8082 / :9092
                 └─────────────┬──────────────┘        └──────┬───────┘      └──────┬───────┘
                               │                              │                     │
                               └──────────────▶  PostgreSQL :5432  ◀────────────────┘
                                 (schema public)     (schemas user_service / product_service / order_service)

  Apps ──metrics──▶ Prometheus ──▶ Grafana ◀── Loki ◀── Fluent Bit ◀──logs── Apps
  cAdvisor (containers) and node-exporter (host) ──▶ Prometheus
```

---

## Table of contents

1. [Tech stack](#1-tech-stack)
2. [Repository layout](#2-repository-layout)
3. [Prerequisites](#3-prerequisites)
4. [Installation](#4-installation)
5. [Configuration](#5-configuration)
6. [Running with Docker (recommended)](#6-running-with-docker-recommended)
7. [Running locally without Docker](#7-running-locally-without-docker)
8. [REST API](#8-rest-api)
9. [gRPC](#9-grpc)
10. [Monitoring](#10-monitoring)
11. [Load testing with Apache JMeter](#11-load-testing-with-apache-jmeter)
12. [Load testing with k6 (optional)](#12-load-testing-with-k6-optional)
13. [Tests and build](#13-tests-and-build)
14. [Troubleshooting](#14-troubleshooting)
15. [Known limitations](#15-known-limitations)

---

## 1. Tech stack

| Area | Technology |
|---|---|
| Language / runtime | Java 25 |
| Framework | Spring Boot 4.1.2 (Web MVC, Data JPA, Actuator, Validation) |
| Service-to-service | Spring gRPC 1.1 (grpc-java 1.84, protobuf 4.36) |
| Database | PostgreSQL 17 (+ pgAdmin) |
| Build | Maven (wrapper `mvnw` included in every module), Lombok |
| Containers | Docker / Docker Compose (profiles) |
| Metrics | Micrometer + Prometheus 3.5, cAdvisor 0.55, node-exporter 1.9 |
| Logs | Spring Boot structured JSON logs → Fluent Bit 4.0 → Loki 3.5 |
| Dashboards | Grafana 12.1 (provisioned automatically) |
| Load testing | Apache JMeter 5.6.3 (main), k6 1.1 (optional) |

---

## 2. Repository layout

```
.
├── docker-compose.yaml          # all containers + the two exclusive profiles
├── .env.example                 # template of the root .env file
├── ecommerceMono/               # monolith (port 8080)
├── user-service/                # users      (REST 8081, gRPC 9091)
├── product-service/             # products   (REST 8082, gRPC 9092)
├── order-service/               # carts/orders (REST 8083, gRPC 9093) + gRPC clients
│   └── src/main/proto/          # .proto contracts (each service has its own copy)
├── monitoring/
│   ├── prometheus/prometheus.yml        # scrape config (Docker service discovery)
│   ├── fluent-bit/                      # log pipeline to Loki
│   └── grafana/
│       ├── provisioning/                # data sources + dashboard provider
│       ├── dashboards/                  # monolith.json, microservices.json
│       └── generate_dashboard.py        # regenerates the two dashboards
└── loadtest/
    ├── jmeter/                  # JMeter plan, properties per architecture, run script
    ├── k6/script.js             # optional k6 scenario
    ├── run-comparison.sh        # optional automated k6 comparison
    ├── report.py                # comparison report for the k6 runs
    └── results/                 # k6 outputs
```

Each Spring Boot module follows the same layout: `controllers`, `dtos`, `entities`, `repositories`
(`reposotories` in the monolith), `services` / `services/implementation`, and `grpc` for the microservices.

---

## 3. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| [Docker Desktop](https://www.docker.com/products/docker-desktop/) | 4.x with Compose v2 (Docker Engine 29 tested) | running everything |
| Docker resources | ≥ 8 CPUs and ≥ 10 GB RAM recommended | the app limits (3 CPU / 3 GB) + monitoring + PostgreSQL |
| JDK | 25 | building / running the apps outside Docker, IDE |
| Apache JMeter | 5.6.3 | load tests |
| Python | 3.x (optional) | regenerating dashboards, k6 report |
| IntelliJ IDEA | optional | development |

Maven does not need to be installed: every module ships the Maven wrapper (`mvnw` / `mvnw.cmd`).

**Free ports required:** 5432, 5050, 8080–8083, 9091–9093, 3000, 3100, 9090, 9100, 8088, 24224, 2020.
A local (Windows) PostgreSQL service on 5432 must be stopped.

---

## 4. Installation

```bash
git clone <repository-url> performence_test
cd performence_test
```

Create the root `.env` file from the template (it is git-ignored):

```bash
cp .env.example .env
```

```powershell
# PowerShell
Copy-Item .env.example .env
```

Build the four application images (the first build downloads Maven dependencies, ~5 min):

```bash
docker compose --profile "*" build
```

Optional, to run a module from the IDE or the command line, build it with the wrapper:

```bash
cd user-service
./mvnw clean package -DskipTests        # Windows: mvnw.cmd clean package -DskipTests
```

### IntelliJ IDEA

1. Open the repository root; the four `pom.xml` files are imported as Maven modules.
2. Maven tool window → **Reload All Maven Projects**, then **Generate Sources and Update Folders**
   (or build once). The gRPC classes are generated from `src/main/proto/*.proto` into
   `target/generated-sources/protobuf`, which the `build-helper-maven-plugin` registers as a source root.
3. Enable annotation processing (Lombok).

---

## 5. Configuration

### 5.1 Root `.env` (used by Docker Compose)

| Variable | Default | Description |
|---|---|---|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | `test` / `test123456789` / `ecommercedb` | database credentials |
| `PGADMIN_DEFAULT_EMAIL` / `PGADMIN_DEFAULT_PASSWORD` | – | pgAdmin login |
| `COMPOSE_PROFILES` | `microservices` | stack started by a plain `docker compose up` |
| `SPRING_PROFILES_ACTIVE` | `dev` | Spring profile of the containers |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75.0` | JVM options of the containers |
| `MONO_CPUS` / `MONO_MEMORY` | `3` / `3g` | limits of the monolith container |
| `SERVICE_CPUS` / `SERVICE_MEMORY` | `1` / `1g` | limits of **each** microservice container |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | `admin` / `admin` | Grafana login |

> By default both architectures get the **same total budget**: monolith 3 CPU / 3 GB vs 3 × (1 CPU / 1 GB).

In the containers, SQL logging is disabled (`SPRING_JPA_SHOW_SQL=false`) and logs are written as JSON
(`LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash`) for both architectures.

### 5.2 Spring profiles

Every application has `application.yaml` (common settings) plus three profiles:

| Profile | Actuator endpoints exposed | Health details | Notes |
|---|---|---|---|
| `dev` (default) | `health`, `info`, `prometheus` | always | |
| `staging` | `health`, `prometheus` | when authorized | |
| `production` | `health`, `prometheus` | when authorized | `info` env disabled |

Select a profile with `SPRING_PROFILES_ACTIVE` (Docker) or `--spring.profiles.active=production` (local).

### 5.3 Application settings (environment variables)

| Variable | Default | Used by |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/ecommercedb` | all |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `test` / `test123456789` | all |
| `USER_HTTP_PORT` / `USER_GRPC_PORT` | `8081` / `9091` | user-service |
| `PRODUCT_HTTP_PORT` / `PRODUCT_GRPC_PORT` | `8082` / `9092` | product-service |
| `ORDER_HTTP_PORT` / `ORDER_GRPC_PORT` | `8083` / `9093` | order-service |
| `USER_GRPC_TARGET` / `PRODUCT_GRPC_TARGET` | `localhost:9091` / `localhost:9092` | order-service (gRPC clients) |
| `ARCHITECTURE` | `local` | metric tag (`monolith` / `microservices` in Docker) |

The applications also import an optional `.env` file from their working directory and its parent.

### 5.4 Ports

| Component | URL / port |
|---|---|
| Monolith | http://localhost:8080 |
| user-service | http://localhost:8081 · gRPC 9091 |
| product-service | http://localhost:8082 · gRPC 9092 |
| order-service | http://localhost:8083 · gRPC 9093 |
| PostgreSQL | localhost:5432 |
| pgAdmin | http://localhost:5050 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Loki | http://localhost:3100 |
| cAdvisor | http://localhost:8088 |
| node-exporter | http://localhost:9100/metrics |
| Fluent Bit | 24224 (log input), http://localhost:2020 (metrics) |

---

## 6. Running with Docker (recommended)

The applications are split into two **exclusive Compose profiles**. PostgreSQL, pgAdmin and the
whole monitoring stack have no profile and always start.

| Profile | Containers | Start command |
|---|---|---|
| `microservices` (default) | user-service, product-service, order-service | `docker compose up -d --build` |
| `monolith` | ecommerce-mono | `docker compose --profile monolith up -d --build` |

### Start

```bash
# microservices + monitoring (default profile from COMPOSE_PROFILES)
docker compose up -d --build --wait

# monolith + monitoring
docker compose --profile monolith up -d --build --wait
```

`--wait` returns once every container is healthy (`/actuator/health`). order-service only starts when
user-service and product-service are healthy.

### Switch from one architecture to the other

Starting a profile does not stop the other one: remove the running applications first so only one
architecture uses the machine.

```bash
# monolith -> microservices
docker compose --profile "*" rm -f -s ecommerce-mono
docker compose up -d --wait

# microservices -> monolith
docker compose --profile "*" rm -f -s user-service product-service order-service
docker compose --profile monolith up -d --wait
```

### Useful commands

```bash
docker compose --profile "*" ps                     # status of every container
docker compose logs -f order-service                # follow the logs of a container
docker compose --profile "*" build user-service     # rebuild one image after a code change
docker compose up -d --build user-service           # rebuild + restart one service
docker compose --profile "*" stop                   # stop everything (keeps containers and data)
docker compose --profile "*" down                   # remove containers (keeps volumes/data)
docker compose --profile "*" down -v                # remove containers AND volumes (database, metrics, logs)
```

Run with another Spring profile:

```powershell
$env:SPRING_PROFILES_ACTIVE="production"; docker compose up -d --wait
```

```bash
SPRING_PROFILES_ACTIVE=production docker compose up -d --wait
```

### Smoke test

```bash
# monolith
curl -X POST localhost:8080/api/v1/users -H "Content-Type: application/json" -d '{"firstName":"A","lastName":"B","email":"a@b.c","phone":"1"}'
curl localhost:8080/api/v1/users

# microservices
curl -X POST localhost:8081/api/v1/users -H "Content-Type: application/json" -d '{"firstName":"A","lastName":"B","email":"a@b.c","phone":"1"}'
curl localhost:8081/api/v1/users
```

---

## 7. Running locally without Docker

Start only the database (and optionally the monitoring) with Docker:

```bash
docker compose up -d postgres
```

Then run the applications with the Maven wrapper (one terminal each). The default profile is `dev`.

```bash
# monolith
cd ecommerceMono && ./mvnw spring-boot:run

# microservices: start user-service and product-service before order-service
cd user-service    && ./mvnw spring-boot:run
cd product-service && ./mvnw spring-boot:run
cd order-service   && ./mvnw spring-boot:run
```

Or run a packaged jar with a profile:

```bash
cd user-service
./mvnw clean package -DskipTests
java -jar target/user-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=staging
```

> Containers started with Docker use the same ports: stop them before running the apps locally.

---

## 8. REST API

The API is identical in both architectures; only the host/port changes.

| Resource | Monolith | Microservices |
|---|---|---|
| `/api/v1/users` | `localhost:8080` | `localhost:8081` (user-service) |
| `/api/v1/products` | `localhost:8080` | `localhost:8082` (product-service) |
| `/api/v1/carts`, `/api/v1/orders` | `localhost:8080` | `localhost:8083` (order-service) |

| Method | Path | Body / header | Response |
|---|---|---|---|
| GET | `/api/v1/users` | – | 200, list of users |
| POST | `/api/v1/users` | `UserRequest` | 201 |
| GET | `/api/v1/users/{id}` | – | 200 / 404 |
| PUT | `/api/v1/users/{id}` | `UserRequest` | 200 / 404 |
| GET | `/api/v1/products` | – | 200, active products |
| POST | `/api/v1/products` | `ProductRequest` | 201 |
| GET | `/api/v1/products/{id}` | – | 200 / 404 |
| PUT | `/api/v1/products/{id}` | `ProductRequest` | 200 / 404 |
| DELETE | `/api/v1/products/{id}` | – | 204 (soft delete: `active=false`) / 404 |
| GET | `/api/v1/products/search?keyword=…` | – | 200, active in-stock products whose name matches |
| POST | `/api/v1/carts` | header `X-User-ID`, `{"productId":1,"quantity":2}` | 201 / 400 (unknown user/product or not enough stock) |
| GET | `/api/v1/carts` | header `X-User-ID` | 200, cart items |
| DELETE | `/api/v1/carts/items/{productId}` | header `X-User-ID` | 204 / 404 |
| POST | `/api/v1/orders` | header `X-User-ID` | 201, order from the cart (the cart is emptied) / 400 (empty cart) |

Request bodies:

```json
// UserRequest
{ "firstName": "Iyed", "lastName": "B", "email": "iyed@test.local", "phone": "000",
  "address": { "street": "s", "city": "Tunis", "zipCode": "1000", "state": "TN", "country": "Tunisia" } }

// ProductRequest
{ "name": "Laptop", "description": "15 inch", "price": 999.99, "stockQuantity": 100,
  "category": "computers", "imageUrl": "http://img/laptop.png" }
```

Full flow example (microservices; use port 8080 everywhere for the monolith):

```bash
curl -X POST localhost:8082/api/v1/products -H "Content-Type: application/json" \
     -d '{"name":"Laptop","price":999.99,"stockQuantity":100}'
curl -X POST localhost:8083/api/v1/carts -H "Content-Type: application/json" -H "X-User-ID: 1" \
     -d '{"productId":1,"quantity":2}'
curl localhost:8083/api/v1/carts -H "X-User-ID: 1"
curl -X POST localhost:8083/api/v1/orders -H "X-User-ID: 1"
```

Differences between the two versions: in the microservices, a cart item only contains `userId` /
`productId` (the monolith returns the full user and product objects), and when user-service or
product-service is unreachable order-service answers **503** (timeout: **504**) instead of 500.

Actuator: `/actuator/health`, `/actuator/info` (dev), `/actuator/prometheus` on every application.

---

## 9. gRPC

order-service validates users and products by calling the other services over gRPC (plaintext,
5 s deadline). Contracts live in `src/main/proto/` and the Java stubs are generated at build time.

| Service | Port | RPC | Called by |
|---|---|---|---|
| `ecommerce.user.UserGrpcService` | 9091 | `GetUser(GetUserRequest) → UserReply` | order-service |
| `ecommerce.product.ProductGrpcService` | 9092 | `GetProduct(GetProductRequest) → ProductReply` | order-service |
| `ecommerce.order.OrderGrpcService` | 9093 | `GetOrder(GetOrderRequest) → OrderReply` | (exposed for other clients) |

- Servers: classes in `<service>/src/main/java/.../grpc/*GrpcServer.java`; Spring gRPC registers them
  automatically on `spring.grpc.server.port`.
- Clients: `order-service/.../grpc/client/` (`GrpcClientConfig`, `UserClient`, `ProductClient`); channel
  targets are set in `spring.grpc.client.channel.<name>.target`.
- When a `.proto` file changes, update the copy in **every** module that uses it
  (`order-service` holds copies of `user.proto` and `product.proto`), then rebuild.

---

## 10. Monitoring

| Tool | Role | URL |
|---|---|---|
| **Grafana** | dashboards (login `admin` / `admin`, anonymous read-only access enabled) | http://localhost:3000 |
| **Prometheus** | metrics storage, scrapes every 5 s | http://localhost:9090 |
| **Loki** | log storage | http://localhost:3100 |
| **Fluent Bit** | receives container logs (Docker `fluentd` driver) and pushes them to Loki | :24224 |
| **cAdvisor** | CPU / memory / network / throttling per container | http://localhost:8088 |
| **node-exporter** | host (Docker VM) CPU and memory | http://localhost:9100 |

### Dashboards (one per architecture)

Grafana folder **Performance test**:

- **Monolith**: http://localhost:3000/d/monolith
- **Microservices**: http://localhost:3000/d/microservices (with a *Service* filter)

Each dashboard contains: overview (status, req/s, p95, 5xx rate, CPU, memory, DB connections), HTTP per
endpoint (rate, p50/p95/p99, status codes), JVM (heap, GC, CPU, threads), HikariCP pool, container
resources vs limits (including CPU throttling), network, PostgreSQL and host, and the logs
(level filter + text search). The Microservices dashboard adds a **gRPC** section (client/server calls,
latency, errors).

### Monitoring follows the running profile

Prometheus uses **Docker service discovery**: it only scrapes running containers that carry the label
`prometheus.scrape=true`. With the monolith profile up it scrapes `ecommerce-mono`; with the
microservices profile it scrapes the three services. Targets: http://localhost:9090/targets.

Metrics are tagged with `application` and `architecture`; logs in Loki have the labels
`job="spring-boot"`, `architecture`, `container`, `level`. Example LogQL in Grafana → Explore:

```
{job="spring-boot", architecture="microservices", level="ERROR"} | json
```

### Changing the dashboards

Edit and run the generator (the JSON files are re-provisioned automatically):

```bash
python monitoring/grafana/generate_dashboard.py
```

Changes made in the Grafana UI are allowed but are overwritten when the files change; export them to
`monitoring/grafana/dashboards/` to keep them.

---

## 11. Load testing with Apache JMeter

Files in `loadtest/jmeter/`:

| File | Content |
|---|---|
| `ecommerce-load-test.jmx` | the test plan (also editable in the JMeter GUI) |
| `monolith.properties` | all endpoints on `localhost:8080` |
| `microservices.properties` | users `:8081`, products `:8082`, carts/orders `:8083` |
| `run-jmeter.ps1` | runs the plan in non-GUI mode and generates the reports |

### What the plan does

1. **setUp thread group**: creates one user per thread and 50 products, then keeps their ids.
2. **Shopping users** (each thread keeps its **own** user, sent in `X-User-ID`), each iteration:
   `GET /products` → `GET /products/{id}` → `GET /products/search` → `GET /users/{id}` →
   `POST /carts` ×2 → `GET /carts` → `POST /orders` → think time.
3. Every request asserts its expected status (200/201). Products are chosen deterministically from
   (thread, iteration), so **both architectures receive exactly the same requests**.

### Recommended workflow (monolith first, then microservices)

```powershell
# 1. Monolith
docker compose --profile "*" rm -f -s user-service product-service order-service
docker compose --profile monolith up -d --wait
.\loadtest\jmeter\run-jmeter.ps1 -Architecture monolith -Duration 60          # warm-up (optional)
.\loadtest\jmeter\run-jmeter.ps1 -Architecture monolith -Threads 50 -RampUp 60 -Duration 300

# 2. Microservices (same parameters!)
docker compose --profile "*" rm -f -s ecommerce-mono
docker compose up -d --wait
.\loadtest\jmeter\run-jmeter.ps1 -Architecture microservices -Duration 60     # warm-up (optional)
.\loadtest\jmeter\run-jmeter.ps1 -Architecture microservices -Threads 50 -RampUp 60 -Duration 300
```

`run-jmeter.ps1` parameters:

| Parameter | Default | Meaning |
|---|---|---|
| `-Architecture` | (required) | `monolith` or `microservices` (the matching stack must be running) |
| `-Threads` | 50 | concurrent virtual users |
| `-RampUp` | 60 | seconds to start all threads |
| `-Duration` | 300 | seconds of load (ramp-up included) |
| `-ThinkMs` | 500 | pause at the end of each iteration (ms) |
| `-JMeterHome` | `$env:JMETER_HOME` or `%USERPROFILE%\Desktop\apache-jmeter-5.6.3` | JMeter installation |

Results go to `loadtest/jmeter/results/<architecture>-<timestamp>/`:

- `results.jtl`: raw samples (CSV)
- `report/index.html`: JMeter HTML dashboard (throughput, percentiles, errors per request)
- `run-info.txt`: parameters, start/end time and the **Grafana link limited to the run window**
- `jmeter.log`

Without the script (any OS), from `loadtest/jmeter/`:

```bash
jmeter -n -t ecommerce-load-test.jmx -q monolith.properties \
       -Jthreads=50 -Jrampup=60 -Jduration=300 -Jthink_ms=500 \
       -l results.jtl -e -o report
```

GUI (for editing/debugging only, never for measurements):

```bash
jmeter -t loadtest/jmeter/ecommerce-load-test.jmx -q loadtest/jmeter/monolith.properties
```

### Rules for a fair comparison

- Run only one architecture at a time, each on a freshly started stack, with the same parameters.
- Warm up before measuring (JIT, connection pools, first gRPC connections), or ignore the first minute.
- Use non-GUI mode; close heavy programs; keep the PC awake during the runs (sleep breaks the timings).
- JMeter shares the CPU with Docker on the same machine: keep the same number of threads for both runs.
- Compare client-side results (JMeter report) **and** server-side resources (Grafana: CPU, memory,
  throttling) over the run window given in `run-info.txt`.

---

## 12. Load testing with k6 (optional)

A k6 version of the same workload runs in Docker and pushes its client-side metrics to Prometheus.

```bash
# the matching stack must be running
docker compose run --rm -e ARCH=monolith k6
docker compose run --rm -e ARCH=microservices k6
docker compose run --rm -e ARCH=microservices -e TEST_PROFILE=smoke k6   # 30 s, 10 VUs
```

Automated comparison (restarts each stack, runs both, writes `loadtest/results/comparison.md`):

```bash
bash loadtest/run-comparison.sh
```

---

## 13. Tests and build

```bash
cd <module>
./mvnw clean package -DskipTests   # build the jar without tests
./mvnw test                        # run the tests
```

The tests are `@SpringBootTest` context-loading tests: they need PostgreSQL on `localhost:5432`
(`docker compose up -d postgres`). For the microservices, the application containers must be stopped
first, because the test context starts a gRPC server on the same ports (9091–9093).

Rebuild the Docker images after any code change:

```bash
docker compose --profile "*" build
```

---

## 14. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `password authentication failed for user "test"` | another PostgreSQL (e.g. a Windows service) listens on 5432: stop it, or change the published port in `docker-compose.yaml` and `DATABASE_URL`. |
| `Cannot resolve symbol UserReply` (or other gRPC classes) in IntelliJ | generated sources not indexed: Maven → *Reload All Maven Projects* + *Generate Sources and Update Folders*; if needed *File → Invalidate Caches*. Happens after `mvn clean` until the next build. |
| Maven `PKIX path building failed` | the JVM does not trust the network's certificate: `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (Windows). |
| `docker compose up` starts no application | `COMPOSE_PROFILES` missing in `.env` (copy `.env.example`) or use `--profile`. |
| order-service answers 503 / 504 | user-service or product-service is down or slow (`docker compose ps`, logs). |
| No container metrics in Grafana | cAdvisor must be ≥ 0.54 with Docker Desktop's containerd image store (0.55 is used). |
| A dashboard shows *No data* | check http://localhost:9090/targets: only the running profile is scraped; select a time range covering the run. |
| JMeter: `Unsupported class file major version 69` | JMeter 5.6.3's Groovy 3 cannot compile scripts referencing most JDK classes on Java 25. The provided plan avoids them; for your own scripts run JMeter with Java 21. |
| `run-jmeter.ps1`: "Nothing healthy on port …" | start the matching profile first. |
| Windows blocks the `.ps1` script | `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`, or `powershell -ExecutionPolicy Bypass -File loadtest\jmeter\run-jmeter.ps1 …` |

---

## 15. Known limitations

- No authentication / authorization (out of scope for this performance study).
- Order lines compute `subTotal = price × quantity` while `price` already holds the line total, in both
  versions (business logic kept identical on purpose).
- Product stock is checked but never decremented.
- Both architectures share one PostgreSQL instance (separate schemas for the microservices); the database
  is not part of the resource budget.
