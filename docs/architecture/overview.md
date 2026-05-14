# VulnWatch Architecture

## Overview
VulnWatch is a distributed vulnerability scanning platform designed with a focus on modularity and separation of concerns. It's built to be intern-friendly, with clear boundaries between components.

## Monorepo Structure
- `api-dotnet/`: The main API layer built with C# and Clean Architecture.
- `worker-java/`: Background workers built with Java and Spring Boot.
- `shared/`: JSON schemas and documentation for shared contracts (like Redis payloads).
- `infra/`: Configuration files for Docker, Redis, and PostgreSQL.
- `docs/`: Architecture diagrams and onboarding guides.

## System Components

### 1. API (.NET)
- **Role**: Entry point for users, manages authentication, scan requests, and result retrieval.
- **Architecture**: Clean Architecture.
- **Key Folders**:
  - `Application/`: Features (Auth, Scans, etc.) using Commands/Handlers.
  - `Domain/`: Core entities and Enums.
  - `Infrastructure/`: Persistence (EF Core), Redis Producers, and external services.
  - `Web/`: API Controllers, Middleware, and Filters.

### 2. Worker (Java)
- **Role**: Background processing of vulnerability scans.
- **Framework**: Spring Boot.
- **Key Folders**:
  - `scanners/`: Specialized classes for different scan types (DNS, SSL, Headers).
  - `processors/`: Orchestrates which scanners to run.
  - `queue/`: Handles Redis message consumption (Listeners).
  - `models/`: Java representations of queue payloads and DB entities.

### 3. Redis
- Acts as the message broker (Queue) between the API and the Workers. Uses Pub/Sub or Streams for job distribution.

### 4. PostgreSQL
- Primary database for storing users, domains, scan history, and findings.

## Data Flow
1. **Request**: User submits a scan request via `POST /api/scans`.
2. **Enqueue**: API validates request, saves a 'Pending' record to DB, and publishes a `ScanJob` to Redis.
3. **Consume**: Java Worker listens to Redis, receives the job, and parses the JSON payload.
4. **Process**: `ScanProcessor` delegates the work to specific scanners (e.g., `DnsScanner`).
5. **Result**: Worker updates the scan record in PostgreSQL with the findings.
