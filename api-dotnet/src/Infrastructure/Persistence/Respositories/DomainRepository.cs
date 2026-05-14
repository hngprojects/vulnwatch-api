using Application.Interfaces;
using Application.Features.Domain;
using Domain.Entities;
using Domain.Enums;
using Microsoft.EntityFrameworkCore;

namespace Infrastructure.Persistence.Repositories;

public sealed class ScannedDomainRepository(VulnWatchDbContext db)
    : BaseRepository<ScannedDomain>(db), IScannedDomainRepository
{
    public Task<ScannedDomain?> FindActive(string domain, CancellationToken ct) =>
        Db.Domains
            .FirstOrDefaultAsync(d =>
                d.DomainName == domain &&
                d.VerificationStatus != VerificationStatus.Revoked, ct);

    public Task<int> CountPending(Guid userId, CancellationToken ct) =>
        Db.Domains
            .CountAsync(d =>
                d.UserId == userId &&
                d.VerificationStatus == VerificationStatus.Pending, ct);

    public Task<ScannedDomain?> FindPendingById(Guid id, Guid userId, CancellationToken ct) =>
        Db.Domains
            .FirstOrDefaultAsync(d =>
                d.Id == id &&
                d.UserId == userId &&
                d.VerificationStatus == VerificationStatus.Pending, ct);

    public Task<ScannedDomain?> GetByNameAndUser(string domainName, Guid userId, CancellationToken ct) =>
        Db.Domains
            .FirstOrDefaultAsync(d =>
                d.DomainName == domainName &&
                d.UserId == userId, ct);

    public async Task<(IReadOnlyList<ScannedDomain>, int)> GetPaged(DomainFilter filter, CancellationToken ct = default)
    {
        var query = Db.Domains.AsNoTracking().AsQueryable();

        query = query
              .Where(d => d.UserId == filter.UserId);

        if (!string.IsNullOrWhiteSpace(filter.Search))
            query = query.Where(d => d.DomainName.Contains(filter.Search));

        if (filter.Status.HasValue)
            query = query.Where(d => d.VerificationStatus == filter.Status.Value);

        var totalCount = await query.CountAsync(ct);

        query = (filter.SortBy, filter.Order) switch
        {
            ("domain", "asc") => query.OrderBy(p => p.DomainName),
            ("domain", "desc") => query.OrderByDescending(p => p.DomainName),
            ("status", "asc") => query.OrderBy(p => p.VerificationStatus),
            ("status", "desc") => query.OrderByDescending(p => p.VerificationStatus),
            ("created_at", "desc") => query.OrderByDescending(p => p.CreatedAt),
            _ => query.OrderBy(p => p.CreatedAt),
        };


        var items = await query
            .Skip((filter.Page - 1) * filter.PageSize)
            .Take(filter.PageSize)
            .ToListAsync(ct);

        return (items, totalCount);
    }

}