using System.Text.Json;
using Application.Services;
using Domain.Enums;
using Domain.Events;
using Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace Web.Workers;

public class SslExpiryChecker : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<SslExpiryChecker> _logger;

    public SslExpiryChecker(
        IServiceScopeFactory scopeFactory,
        ILogger<SslExpiryChecker> logger)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            await CheckExpiringCertificates(ct);
            // Run once per day
            await Task.Delay(TimeSpan.FromHours(24), ct);
        }
    }

    private async Task CheckExpiringCertificates(CancellationToken ct)
    {
        using var scope = _scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<VulnWatchDbContext>();
        var dispatcher = scope.ServiceProvider.GetRequiredService<AlertDispatcher>();

        // Find SSL findings with expiry payloads
        var sslFindings = await db.Findings
            .Include(f => f.Scan)
            .ThenInclude(s => s.Domain)
            .Where(f =>
                f.Surface == FindingSurface.Ssl &&
                f.Status == FindingStatus.Open &&
                f.TechnicalPayload != null)
            .ToListAsync(ct);

        foreach (var finding in sslFindings)
        {
            try
            {
                var payload = JsonSerializer.Deserialize<SslPayload>(
                    finding.TechnicalPayload!);

                if (payload?.ExpiresAt is null) continue;

                var daysRemaining = (payload.ExpiresAt.Value - DateTime.UtcNow).Days;

                // Alert at 30, 14, 7 days
                if (daysRemaining is 30 or 14 or 7 or <= 3)
                {
                    await dispatcher.DispatchAsync(new SslExpiryEvent(
                        DomainId: finding.Scan.Domain!.Id,
                        UserId: finding.Scan.UserId,
                        DomainName: finding.Scan.Domain.DomainName,
                        ExpiresAt: payload.ExpiresAt.Value,
                        DaysRemaining: daysRemaining), ct);
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "SSL expiry check failed for finding {FindingId}",
                    finding.Id);
            }
        }
    }
}

public record SslPayload(DateTime? ExpiresAt, string? Issuer, string? CommonName);