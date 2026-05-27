

using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Microsoft.EntityFrameworkCore;

namespace Infrastructure.Persistence.Repositories;

public sealed class AlertRepository(VulnWatchDbContext db)
    : BaseRepository<Alert>(db), IAlertRepository
{
    public Task<List<Alert>> GetRecentByDomain(Guid domainId, int limit, CancellationToken ct) =>
        Db.Alerts
            .Where(a => a.DomainId == domainId)
            .OrderByDescending(a => a.CreatedAt)
            .Take(limit)
            .ToListAsync(ct);
    public Task<List<Alert>> GetPendingAsync(int batchSize, CancellationToken ct) =>
        Db.Alerts
            .Where(a => a.Status == OutboxStatus.Pending && a.NumRetries < 3)
            .OrderBy(a => a.CreatedAt)
            .Take(batchSize)
            .ToListAsync(ct);

    public Task<bool> HasRecentAlert(Guid userId, AlertType type,
        Guid? domainId, TimeSpan window, CancellationToken ct)
    {
        var cutoff = DateTime.UtcNow - window;
        return Db.Alerts.AnyAsync(a =>
            a.UserId == userId &&
            a.Type == type &&
            a.DomainId == domainId &&
            a.CreatedAt >= cutoff, ct);
    }

    public void DetachUnsavedAlerts() {
        foreach (var entry in Db.ChangeTracker.Entries<Alert>()
                    .Where(e => e.State == EntityState.Added).ToList())
            entry.State = EntityState.Detached;
    }
}