using Application.Features.Dashboard.DTOs;
using Application.Interfaces;
using Domain.Common;
using MediatR;
using Microsoft.EntityFrameworkCore;

namespace Application.Features.Dashboard;

public record GetDashboardAlertsQuery(int Limit = 10)
    : IRequest<Result<IReadOnlyList<DashboardAlertDto>>>;

public class GetDashboardAlertsHandler(
    IVulnWatchDbContext db,
    ICurrentUser currentUser)
    : IRequestHandler<GetDashboardAlertsQuery,
                      Result<IReadOnlyList<DashboardAlertDto>>>
{
    public async Task<Result<IReadOnlyList<DashboardAlertDto>>> Handle(
        GetDashboardAlertsQuery query, CancellationToken ct)
    {
        var userId = currentUser.UserId;
        var limit  = Math.Min(query.Limit, 50);

        var alerts = await db.Alerts
            .Where(a => a.UserId == userId)
            .OrderByDescending(a => a.CreatedAt)
            .Take(limit)
            .Select(a => new DashboardAlertDto(
                a.Id,
                a.DomainId,
                // Join domain name inline — avoids a separate query
                db.Domains
                    .Where(d => d.Id == a.DomainId)
                    .Select(d => (string?)d.DomainName)
                    .FirstOrDefault(),
                a.Type,
                a.Severity,
                a.Subject,
                a.Status,
                a.CreatedAt))
            .ToListAsync(ct);

        return Result<IReadOnlyList<DashboardAlertDto>>.Success(alerts);
    }
}