using Application.Features.Dashboard.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;
using Microsoft.EntityFrameworkCore;

namespace Application.Features.Dashboard;

public record GetDashboardSummaryQuery : IRequest<Result<DashboardSummaryDto>>;

public class GetDashboardSummaryHandler(
    IVulnWatchDbContext db,
    ICurrentUser currentUser)
    : IRequestHandler<GetDashboardSummaryQuery, Result<DashboardSummaryDto>>
{
    public async Task<Result<DashboardSummaryDto>> Handle(
        GetDashboardSummaryQuery _, CancellationToken ct)
    {
        var userId = currentUser.UserId;
        var today  = DateTime.UtcNow.Date;

        // Single projection — all aggregation in one round trip
        var domainData = await db.Domains
            .Where(d => d.UserId == userId)
            .Select(d => new
            {
                d.Id,
                d.DomainName,
                d.VerificationStatus,
                d.SslCertExpiry,

                IsMonitoringEnabled = db.DomainSettings
                    .Where(s => s.DomainId == d.Id)
                    .Select(s => (bool?)s.MonitoringEnabled)
                    .FirstOrDefault() ?? false,

                LatestScore = d.Scans
                    .Where(s => s.Status == ScanStatus.Completed)
                    .OrderByDescending(s => s.CompletedAt)
                    .Select(s => (int?)s.SecurityScore)
                    .FirstOrDefault(),

                LatestScanId = d.Scans
                    .Where(s => s.Status == ScanStatus.Completed)
                    .OrderByDescending(s => s.CompletedAt)
                    .Select(s => (Guid?)s.Id)
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
                    .Count(f => f.Status == FindingStatus.Open)
            })
            .ToListAsync(ct);

        // Active SSL expiry alerts — separate query, small result set
        var sslAlertCount = await db.Alerts
            .Where(a => a.UserId == userId
                     && a.Type == AlertType.SslExpiry
                     && a.Status == OutboxStatus.Pending)
            .CountAsync(ct);

        // In-memory aggregation
        var verified   = domainData.Count(d =>
            d.VerificationStatus == VerificationStatus.Verified);
        var monitoring = domainData.Count(d => d.IsMonitoringEnabled);

        var scores = domainData
            .Where(d => d.LatestScore.HasValue)
            .Select(d => d.LatestScore!.Value)
            .ToList();

        var avgScore = scores.Count > 0
            ? (int)Math.Round(scores.Average())
            : (int?)null;

        var overallPosture = ClassifyPosture(avgScore, domainData
            .Sum(d => d.CriticalCount));

        // Most urgent SSL — domain with fewest days left above zero
        var mostUrgentSsl = domainData
            .Where(d => d.SslCertExpiry.HasValue)
            .Select(d => new
            {
                d.Id,
                d.DomainName,
                Days = (d.SslCertExpiry!.Value.UtcDateTime.Date - today).Days
            })
            .Where(d => d.Days >= 0)
            .OrderBy(d => d.Days)
            .Select(d => new SslUrgentDto(
                d.Id,
                d.DomainName,
                d.Days,
                ClassifySslSeverity(d.Days)))
            .FirstOrDefault();

        // Most recent scan across all domains
        var mostRecent = domainData
            .Where(d => d.LatestScanAt.HasValue && d.LatestScanId.HasValue)
            .OrderByDescending(d => d.LatestScanAt)
            .Select(d => new LastScanDto(
                d.LatestScanId!.Value,
                d.Id,
                d.DomainName,
                d.LatestScore,
                d.LatestScanAt!.Value))
            .FirstOrDefault();

        return Result<DashboardSummaryDto>.Success(new DashboardSummaryDto(
            TotalDomains:           domainData.Count,
            VerifiedDomains:        verified,
            MonitoringActiveDomains: monitoring,
            OverallPosture:         overallPosture,
            AvgSecurityScore:       avgScore,
            TotalCriticalFindings:  domainData.Sum(d => d.CriticalCount),
            TotalOpenFindings:      domainData.Sum(d => d.OpenCount),
            SslAlertsActive:        sslAlertCount,
            MostUrgentSsl:          mostUrgentSsl,
            MostRecentScan:         mostRecent
        ));
    }

    private static string ClassifyPosture(int? avg, int totalCritical) =>
        totalCritical > 0 ? "at_risk" :
        avg is null      ? "unscanned" :
        avg >= 80        ? "safe" :
        avg >= 60        ? "moderate" :
                           "at_risk";

    private static SslSeverity ClassifySslSeverity(int days) => days switch
    {
        <= 0  => SslSeverity.Expired,
        <= 7  => SslSeverity.Critical,
        <= 15 => SslSeverity.Urgent,
        <= 30 => SslSeverity.Warning,
        _     => SslSeverity.Safe
    };
}