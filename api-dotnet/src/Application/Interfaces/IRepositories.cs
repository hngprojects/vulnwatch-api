using Application.Features.Domain;
using Application.Features.Scans;
using Domain.Entities;

namespace Application.Interfaces;

public interface IRepository<T> where T : class
{
    Task AddAsync(T entity, CancellationToken ct = default);
    void Update(T entity);
    void Remove(T entity);
    Task SaveChangesAsync(CancellationToken ct = default);
}

public interface IRefreshTokenRepository : IRepository<RefreshToken>
{
    Task<RefreshToken?> GetById(Guid id, CancellationToken ct = default);
    Task<RefreshToken?> GetByToken(string rawToken, CancellationToken ct = default);
    Task<List<RefreshToken>> GetActiveByUserId(Guid userId, CancellationToken ct = default);

}

public interface IDomainRepository : IRepository<ScannedDomain>
{
    Task<ScannedDomain?> GetById(Guid domainId, CancellationToken ct = default);
    Task<ScannedDomain?> FindUserDomainById(Guid userId, Guid domainId, CancellationToken ct);
    Task<ScannedDomain?> FindActive(string domain, CancellationToken ct);
    Task<ScannedDomain?> FindUserDomainByName(Guid userId, string domain, CancellationToken ct);
    Task<ScannedDomain?> FindUserVerifiedDomainByName(Guid userId, string domain, CancellationToken ct);
    Task<int> CountPending(Guid userId, CancellationToken ct);
    Task<ScannedDomain?> FindPendingById(Guid domainId, Guid userId, CancellationToken ct);
    public Task<ScannedDomain?> GetByNameAndUser(string domainName, Guid userId, CancellationToken ct);
    Task<(IReadOnlyList<ScannedDomain>, int)> GetPaged(DomainFilter q, CancellationToken ct = default);
}

public interface IScanRepository : IRepository<Scan>
{
    Task<Scan?> FindRunningByDomain(Guid domainId, CancellationToken ct);
    Task<Scan?> FindByIdempotencyKey(Guid key, CancellationToken ct);
    Task<(List<Scan> Items, int TotalCount)> GetPaged(ScanFilter filter, CancellationToken ct);
}