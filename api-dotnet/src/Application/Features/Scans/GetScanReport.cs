using Application.Features.Scans.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;
using Microsoft.AspNetCore.Http;


namespace Application.Features.Scans;
public record GetScanReportQuery(Guid ScanId) : IRequest<Result<ScanReportDto>>;

public class GetScanReportHandler(
    IScanRepository scanRepo,
    IDomainRepository domainRepo,
    ICurrentUser currentUser)
    : IRequestHandler<GetScanReportQuery, Result<ScanReportDto>>
{
    public async Task<Result<ScanReportDto>> Handle(GetScanReportQuery query, CancellationToken ct)
    {
        var scan = await scanRepo.FindByIdWithFindings(query.ScanId, ct);

        if (scan is null || scan.UserId != currentUser.UserId)
            return Result<ScanReportDto>.Failure(Error.NotFound("Scan not found."));

        if (scan.Status != ScanStatus.Completed)
            return Result<ScanReportDto>.Failure(
                Error.Validation($"Scan is not complete. Current status: {scan.Status}"));

        // var domain = await domainRepo.GetById(scan.DomainId!.Value, ct);
        if (scan.DomainId is null)
            return Result<ScanReportDto>.Failure(Error.Validation("Scan is not associated with a domain."));

        var domain = await domainRepo.GetById(scan.DomainId.Value, ct);
        if (domain is null)
            return Result<ScanReportDto>.Failure(Error.NotFound("Domain not found."));

        var findings = scan.Findings.ToList();

        var criticalFindings = findings
            .Where(f => f.Severity == FindingSeverity.Critical && f.Status == FindingStatus.Open)
            .ToList();

        var highFindings = findings
            .Where(f => f.Severity == FindingSeverity.High && f.Status == FindingStatus.Open)
            .ToList();

        var summary = new ScanSummaryDto(
            CriticalIssues: criticalFindings.Select(f => f.Title).ToList(),
            HighSeverityIssues: highFindings.Select(f => f.Title).ToList(),
            GoodNews: BuildGoodNews(findings)
        );

        var groups = new FindingGroupsDto(
            CriticalCount: criticalFindings.Count,
            HighCount: highFindings.Count,
            MediumCount: findings.Count(f => f.Severity == FindingSeverity.Medium && f.Status == FindingStatus.Open),
            LowCount: findings.Count(f => f.Severity == FindingSeverity.Low && f.Status == FindingStatus.Open),
            PassCount: findings.Count(f => f.Status == FindingStatus.Remediated)
        );

        var subScores = BuildSubScores(findings);

        return Result<ScanReportDto>.Success(new ScanReportDto(
            ScanId: scan.Id,
            DomainId: domain!.Id,
            DomainName: domain.DomainName,
            DomainStatus: domain.VerificationStatus,
            Status: scan.Status,
            Coverage: scan.Coverage,
            SecurityScore: scan.SecurityScore,
            RiskLevel: ClassifyRisk(scan.SecurityScore),
            CompletedAt: scan.CompletedAt,
            Summary: summary,
            FindingGroups: groups,
            SubScores: subScores
        ));
    }

    private static string ClassifyRisk(int? score) => score switch
    {
        >= 80 => "safe",
        >= 60 => "moderate",
        _ => "at_risk"
    };

    private static string BuildGoodNews(List<Finding> findings)
    {
        var configOnly = findings.All(f =>
            f.Surface == FindingSurface.HttpHeaders || f.Surface == FindingSurface.Dns);

        return configOnly
            ? "These are configuration fixes that don't require code changes."
            : "Review the findings above for remediation guidance.";
    }

    private static SubScoresDto BuildSubScores(List<Finding> findings)
    {
        var sslFindings = findings.Where(f => f.Surface == FindingSurface.Ssl).ToList();
        var dnsFindings = findings.Where(f => f.Surface == FindingSurface.Dns).ToList();
        var httpFindings = findings.Where(f => f.Surface == FindingSurface.HttpHeaders).ToList();

        return new SubScoresDto(
            Exposure: ScoreFromFindings(httpFindings, "Exposure"),
            Ssl: ScoreFromFindings(sslFindings, "SSL"),
            Dns: ScoreFromFindings(dnsFindings, "DNS")
        );
    }

    private static SubScoreItem ScoreFromFindings(List<Finding> findings, string label)
    {
        if (!findings.Any())
            return new SubScoreItem(100, "Pass", $"No {label} issues found.");

        var hasCritical = findings.Any(f => f.Severity == FindingSeverity.Critical);
        var hasHigh = findings.Any(f => f.Severity == FindingSeverity.High);

        var score = hasCritical ? Random.Shared.Next(30, 50)
                  : hasHigh    ? Random.Shared.Next(55, 70)
                  :              Random.Shared.Next(75, 85);

        var status = hasCritical ? "Critical" : hasHigh ? "Warning" : "Pass";
        var detail = findings.First().Title;

        return new SubScoreItem(score, status, detail);
    }
}