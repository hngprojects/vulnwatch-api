# Onboarding Guide for Interns

Welcome to VulnWatch! This guide will help you set up your environment and understand how to contribute.

## 🛠 Prerequisites
- [Docker & Docker Compose](https://www.docker.com/get-started)
- [.NET 8 SDK](https://dotnet.microsoft.com/download)
- [Java 17 (JDK)](https://adoptium.net/)
- [IDE] (Rider, IntelliJ, or VS Code)

## 🚀 Getting Started

1. **Clone the repository**
2. **Spin up Infrastructure**
   ```bash
   docker-compose up -d
   ```
   This starts PostgreSQL and Redis.
3. **Run the API**
   ```bash
   cd api-dotnet/src/Web
   dotnet run
   ```
4. **Run the Worker**
   ```bash
   cd worker-java
   mvn spring-boot:run
   ```

## 📂 Where do I put my code?

### Adding a new Scan Type?
1. **Shared**: Update `shared/scan-job.schema.json` if needed.
2. **Worker**: 
   - Add a new scanner in `worker-java/src/main/java/com/vulnwatch/worker/scanners/[type]/`.
   - Update `ScanProcessor` to include the new scanner.
3. **API**: Update the `ScanTypes` enum in `Domain/Enums/`.

### Adding a new API Endpoint?
1. **Domain**: Define your entity.
2. **Application**: Add a Feature (Command/Query + Handler).
3. **Web**: Add a Controller method.

## 💡 Tips for Beginners
- Keep methods small (less than 20 lines where possible).
- Use descriptive variable names (e.g., `scanId` instead of `id`).
- Always check the `docs/architecture` folder before starting a new task.
