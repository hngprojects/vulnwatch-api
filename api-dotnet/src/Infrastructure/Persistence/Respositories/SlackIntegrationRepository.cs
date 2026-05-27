using Application.Interfaces;
using Domain.Entities;
using Microsoft.EntityFrameworkCore;

namespace Infrastructure.Persistence.Repositories;

public sealed class SlackIntegrationRepository(VulnWatchDbContext db)
    : BaseRepository<SlackIntegration>(db), ISlackIntegrationRepository
{
    public Task<SlackIntegration?> GetByUserId(Guid userId, CancellationToken ct) =>
        Db.SlackIntegrations.FirstOrDefaultAsync(s => s.UserId == userId, ct);

    public Task<SlackIntegration?> GetActiveByUserId(Guid userId, CancellationToken ct) =>
        Db.SlackIntegrations.FirstOrDefaultAsync(
            s => s.UserId == userId && s.IsActive, ct);
}