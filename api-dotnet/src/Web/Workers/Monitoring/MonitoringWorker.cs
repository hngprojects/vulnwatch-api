using Application.Interfaces;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Web.Workers.Monitoring;

public sealed class MonitoringWorker(
    IServiceScopeFactory scopeFactory,
    ILogger<MonitoringWorker> logger) : BackgroundService
{
    // How often the worker wakes up to check for due domains
    // Short interval is fine — GetDueForScan uses a filtered index
    private static readonly TimeSpan TickInterval = TimeSpan.FromMinutes(1);

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        logger.LogInformation("MonitoringWorker started");

        // Stagger startup by 30s so the app is fully initialised
        await Task.Delay(TimeSpan.FromSeconds(30), ct);

        while (!ct.IsCancellationRequested)
        {
            try
            {
                await RunTickAsync(ct);
            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                logger.LogError(ex, "MonitoringWorker tick failed");
            }

            await Task.Delay(TickInterval, ct);
        }

        logger.LogInformation("MonitoringWorker stopped");
    }

    private async Task RunTickAsync(CancellationToken ct)
    {
        // Resolve only the repository here — it's just a DB read, single scope is fine
        List<Domain.Entities.DomainSettings> due;
        using (var fetchScope = scopeFactory.CreateScope())
        {
            var settingsRepo = fetchScope.ServiceProvider
                .GetRequiredService<IDomainSettingsRepository>();
            due = await settingsRepo.GetDueForScan(DateTime.UtcNow, ct);
        }

        if (due.Count == 0)
        {
            logger.LogDebug("MonitoringWorker tick — no domains due");
            return;
        }

        logger.LogInformation(
            "MonitoringWorker tick — {Count} domain(s) due for monitoring",
            due.Count);

        var semaphore = new SemaphoreSlim(5);

        var tasks = due.Select(async settings =>
        {
            await semaphore.WaitAsync(ct);
            try
            {
                // Each domain task gets its own isolated scope → its own DbContext
                using var scope = scopeFactory.CreateScope();

                var settingsRepo = scope.ServiceProvider
                    .GetRequiredService<IDomainSettingsRepository>();
                var scanDispatch = scope.ServiceProvider
                    .GetRequiredService<ScanDispatchService>();
                var sslCheck = scope.ServiceProvider
                    .GetRequiredService<SslExpiryCheckService>();
                var ownershipCheck = scope.ServiceProvider
                    .GetRequiredService<OwnershipCheckService>();

                await ProcessDomainAsync(
                    settings, scanDispatch, sslCheck, ownershipCheck,
                    settingsRepo, ct);
            }
            catch (Exception ex)
            {
                logger.LogError(ex,
                    "Error processing domain {DomainId} in monitoring worker",
                    settings.DomainId);
            }
            finally
            {
                semaphore.Release();
            }
        });

        await Task.WhenAll(tasks);
    }

    private async Task ProcessDomainAsync(
        Domain.Entities.DomainSettings settings,
        ScanDispatchService scanDispatch,
        SslExpiryCheckService sslCheck,
        OwnershipCheckService ownershipCheck,
        IDomainSettingsRepository settingsRepo,
        CancellationToken ct)
    {
        var domainName = settings.Domain.DomainName;

        logger.LogDebug("Processing monitoring for {Domain}", domainName);

        // Run the three checks — each is independent, failures are isolated
        await RunGuarded(() => scanDispatch.DispatchAsync(settings, ct),
            "scan dispatch", domainName);

        await RunGuarded(() => sslCheck.CheckAsync(settings, ct),
            "SSL expiry check", domainName);

        await RunGuarded(() => ownershipCheck.CheckAsync(settings, ct),
            "ownership check", domainName);

        // Always advance NextScheduledAt even if some checks failed —
        // we don't want a broken domain to block the queue
        settings.RecordMonitoringRun();
        await settingsRepo.SaveChangesAsync(ct);

        logger.LogInformation(
            "Monitoring complete for {Domain} — next run at {Next:u}",
            domainName, settings.NextScheduledAt);
    }

    private async Task RunGuarded(
        Func<Task> action,
        string stepName,
        string domainName)
    {
        try
        {
            await action();
        }
        catch (Exception ex)
        {
            logger.LogError(ex,
                "Monitoring step '{Step}' failed for {Domain}",
                stepName, domainName);
        }
    }
}