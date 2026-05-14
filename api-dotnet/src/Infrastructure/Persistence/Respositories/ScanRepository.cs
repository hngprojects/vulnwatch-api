using Application.Interfaces;
using Application.Features.Domain;
using Domain.Entities;
using Domain.Enums;
using Microsoft.EntityFrameworkCore;

namespace Infrastructure.Persistence.Repositories;

public sealed class ScanRepository(VulnWatchDbContext db)
    : BaseRepository<Scan>(db), IScanRepository
{
    public Task<Scan?> FindRunningByDomain(Guid domainId, CancellationToken ct) =>
        Db.Scans
            .FirstOrDefaultAsync(s =>
                s.DomainId == domainId &&
                (s.Status == ScanStatus.Queued || s.Status == ScanStatus.Running), ct);

    public Task<Scan?> FindByIdempotencyKey(Guid key, CancellationToken ct) =>
        Db.Scans
            .FirstOrDefaultAsync(s =>
                s.IdempotencyKey == key &&
                (s.Status == ScanStatus.Queued || s.Status == ScanStatus.Running), ct);

}

