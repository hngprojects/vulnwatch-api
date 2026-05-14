# Vulnwatch

Vulnwatch is a scalable, event-driven domain security scanning platform. It allows users to register domains, verify ownership via DNS records, and trigger asynchronous security scans. The core feature of the platform is its ability to not only scan for vulnerabilities but also process the results through an AI translation layer to generate human-readable Remediation Cards.

## 🏗 System Architecture

The system is built on a distributed microservice architecture to decouple client-facing web traffic from heavy, long-running AI tasks.

- **Frontend (Client Layer):** Split into two separate Next.js repositories (Landing Page & Dashboard WebApp) to optimize deployment. It communicates exclusively via REST HTTP. Authentication supports both email/password and Google OAuth.
- **Core API (The Front Door):** A C# .NET API. It handles all frontend traffic, user sessions (JWT), domain management, and Postgres database writes. When a user requests a scan, the C# API creates a pending record and drops a job into the message broker.
- **Message Broker:** A Redis queue that facilitates lightning-fast internal communication between the microservices.
- **AI Worker (The Engine):** A headless Java Spring Boot service listening to Redis. It picks up scan jobs, executes the AI translation for the remediation steps, updates the Postgres database, and completes the lifecycle.

## 🚀 Getting Started

Follow these steps to set up the Vulnwatch backend locally.

### Prerequisites

- [Docker & Docker Compose](https://www.docker.com/get-started)
- [.NET 8 SDK](https://dotnet.microsoft.com/download)
- [Java 17 (JDK)](https://adoptium.net/)
- Maven (for running the Java worker)
- An IDE (e.g., Rider, IntelliJ, or VS Code)

### Setup Instructions

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd vulnwatch-be
   ```

2. **Spin up Infrastructure**
   Vulnwatch uses PostgreSQL and Redis. You can start these using Docker Compose:
   ```bash
   docker-compose up -d
   ```

3. **Run the Core API (.NET)**
   Navigate to the API directory and run the application:
   ```bash
   cd api-dotnet/src/Web
   dotnet run
   ```
   The API will be available for handling REST requests and will apply pending EF Core migrations on startup.

4. **Run the AI Worker (Java)**
   In a new terminal, navigate to the worker directory and run the Spring Boot application:
   ```bash
   cd worker-java
   ./mvnw spring-boot:run
   ```
   The worker will start listening to the Redis queue for incoming scan jobs.

## 📂 Project Structure

- `api-dotnet/`: The main API layer built with C# and Clean Architecture.
- `worker-java/`: Background workers built with Java and Spring Boot.
- `shared/`: JSON schemas for shared contracts (e.g., Redis payloads).
- `docs/`: Additional documentation and onboarding guides.

## 🗃 Schema Migrations

The .NET API uses EF Core migrations stored in `api-dotnet/src/Infrastructure/Migrations`.

- Create a migration:
  ```bash
  dotnet ef migrations add <MigrationName> --project api-dotnet/src/Infrastructure/Infrastructure.csproj --startup-project api-dotnet/src/Infrastructure/Infrastructure.csproj --output-dir Migrations
  ```
- Apply migrations manually:
  ```bash
  dotnet ef database update --project api-dotnet/src/Infrastructure/Infrastructure.csproj --startup-project api-dotnet/src/Infrastructure/Infrastructure.csproj
  ```

## 💡 Contributing

For more detailed information on how to contribute, please refer to the [Onboarding Guide](docs/onboarding/getting-started.md).
