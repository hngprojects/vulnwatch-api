using Application.Features.Dashboard.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Enums;
using MediatR;
using Microsoft.EntityFrameworkCore;

namespace Application.Features.Dashboard;

public record GetDashboardDomainsQuery(
    int Page     = 1,
    int PageSize = 10)
    : IRequest<Result<PagedResult<DashboardDomainRowDto>>>;

public class GetDashboardDomainsHandler(
    IVulnWatchDbContext db,
    ICurrentUser currentUser)
    : IRequestHandler<GetDashboardDomainsQuery,
                      Result<PagedResult<DashboardDomainRowDto>>>
{
    public async Task<Result<PagedResult<DashboardDomainRowDto>>> Handle(
        GetDashboardDomainsQuery query, CancellationToken ct)
    {
        var userId   = currentUser.UserId;
        var today    = DateTime.UtcNow.Date;
        var pageSize = Math.Min(query.PageSize, 50);

        var rows = await db.Domains
            .Where(d => d.UserId == userId)
            .OrderByDescending(d => d.Scans
                .Where(s => s.Status == ScanStatus.Completed)
                .OrderByDescending(s => s.CompletedAt)
                .Select(s => (int?)s.SecurityScore)
                .FirstOrDefault() ?? 101)   // unscanned sorts last
            .Select(d => new
            {
                d.Id,
                d.DomainName,

                IsMonitoringEnabled = db.DomainSettings
                    .Where(s => s.DomainId == d.Id)
                    .Select(s => (bool?)s.MonitoringEnabled)
                    .FirstOrDefault() ?? false,

                LatestScore = d.Scans
                    .Where(s => s.Status == ScanStatus.Completed)
                    .OrderByDescending(s => s.CompletedAt)
                    .Select(s => (int?)s.SecurityScore)
                    .FirstOrDefault(),

                LatestScanAt = d.Scans
                    .Where(s => s.Status == ScanStatus.Completed)
                    .OrderByDescending(s => s.CompletedAt)
                    .Select(s => (DateTime?)s.CompletedAt)
                    .FirstOrDefault(),

                CriticalCount = d.Scans
                    .Where(s => s.Status == ScanStatus.Completed)
                    .OrderByDescending(s => s.CompletedAt)
                    .Take(1)
                    .SelectMany(s => s.Findings)
                    .Count(f => f.Status == FindingStatus.Open
                             && f.Severity == FindingSeverity.Critical),

                OpenCount = d.Scans
                    .Where(s => s.Status == ScanStatus.Completed)
                    .OrderByDescending(s => s.CompletedAt)
                    .Take(1)
                    .SelectMany(s => s.Findings)
                    .Count(f => f.Status == FindingStatus.Open),

                SslDays = d.SslCertExpiry == null
                    ? (int?)null
                    : (int)(d.SslCertExpiry.Value.UtcDateTime.Date - today).TotalDays
            })
            .ToListAsync(ct);

        var totalCount = rows.Count;

        var dtos = rows
            .Skip((query.Page - 1) * pageSize)
            .Take(pageSize)
            .Select(r => new DashboardDomainRowDto(
                DomainId:         r.Id,
                DomainName:       r.DomainName,
                MonitoringEnabled: r.IsMonitoringEnabled,
                SecurityScore:    r.LatestScore,
                RiskLevel:        ClassifyRisk(r.LatestScore),
                SslDaysRemaining: r.SslDays,
                SslSeverity:      ClassifySsl(r.SslDays),
                CriticalFindings: r.CriticalCount,
                TotalOpenFindings: r.OpenCount,
                LastScannedAt:    r.LatestScanAt))
            .ToList();

        return Result<PagedResult<DashboardDomainRowDto>>.Success(
            PagedResult<DashboardDomainRowDto>.From(
                dtos, totalCount, query.Page, pageSize,
                "/api/dashboard/domains", string.Empty));
    }

    private static string ClassifyRisk(int? score) => score switch
    {
        >= 80 => "safe",
        >= 60 => "moderate",
        null  => "unscanned",
        _     => "at_risk"
    };

    private static SslSeverity ClassifySsl(int? days) => days switch
    {
        null  => SslSeverity.Unknown,
        <= 0  => SslSeverity.Expired,
        <= 7  => SslSeverity.Critical,
        <= 15 => SslSeverity.Urgent,
        <= 30 => SslSeverity.Warning,
        _     => SslSeverity.Safe
    };
}