using Application.Features.Scans.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;
using Microsoft.AspNetCore.Http;
using System.Text.Json;


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

        var mediumFindings = findings
            .Where(f => f.Severity == FindingSeverity.Medium && f.Status == FindingStatus.Open)
            .ToList();

        var lowFindings = findings
            .Where(f => f.Severity == FindingSeverity.Low && f.Status == FindingStatus.Open)
            .ToList();

        var summary = new ScanSummaryDto(
            CriticalIssues: criticalFindings.Select(ToFindingItem).ToList(),
            HighSeverityIssues: highFindings.Select(ToFindingItem).ToList(),
            MediumSeverityIssues: mediumFindings.Select(ToFindingItem).ToList(),
            LowSeverityIssues: lowFindings.Select(ToFindingItem).ToList(),
            GoodNews: BuildGoodNews(findings),
            TopRecommendation: BuildTopRecommendation(criticalFindings, highFindings, mediumFindings),
            Headline: BuildHeadline(scan.SecurityScore, findings)
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

    private static FindingItemDto ToFindingItem(Finding f)
    {
        var steps = f.RemediationSteps?
            .Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .ToList() ?? [];

        return new FindingItemDto(
            Id: f.Id,
            Surface: f.Surface.ToString(),
            Severity: f.Severity.ToString(),
            Title: f.Title,
            CveId: f.CveId,
            Explanation: f.AiExplanation ?? "No explanation available.",
            RemediationSteps: steps,
            TechnicalDetail: DeserializePayload(f.TechnicalPayload),
            Status: f.Status
        );
    }

    private static object? DeserializePayload(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        try
        {
            return JsonSerializer.Deserialize<JsonElement>(json);
        }
        catch
        {
            return null;
        }
    }

    private static string BuildHeadline(int? score, List<Finding> findings)
    {
        var open = findings.Count(f => f.Status == FindingStatus.Open);
        var risk = score switch { >= 80 => "low", >= 60 => "moderate", _ => "high" };
        return $"{open} finding{(open == 1 ? "" : "s")} detected — overall risk is {risk}.";
    }

    private static string? BuildTopRecommendation(
        List<Finding> critical, List<Finding> high, List<Finding> medium)
    {
        var topFinding = critical.FirstOrDefault()
                      ?? high.FirstOrDefault()
                      ?? medium.FirstOrDefault();

        if (topFinding is null) return null;

        var firstStep = topFinding.RemediationSteps?
            .Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .FirstOrDefault();

        return firstStep is not null
            ? $"{topFinding.Title} — {firstStep}"
            : topFinding.Title;
    }

    private static string BuildGoodNews(List<Finding> findings)
    {
        var open = findings.Where(f => f.Status == FindingStatus.Open).ToList();
        if (!open.Any()) return "No open findings — your domain is clean across all surfaces.";

        var surfaces = open.Select(f => f.Surface).Distinct().ToList();
        var passing = Enum.GetValues<FindingSurface>()
            .Except(surfaces)
            .Select(s => s.ToString())
            .ToList();

        if (!passing.Any())
            return "Review the findings above for remediation guidance.";

        return $"{string.Join(" and ", passing)} checks passed with no issues.";
    }

    private static SubScoreItem ScoreFromFindings(List<Finding> findings, string label)
    {
        var open = findings.Where(f => f.Status == FindingStatus.Open).ToList();
        if (!open.Any())
            return new SubScoreItem(100, "Pass", $"No {label} issues",
                $"All {label} checks passed.", [], null);

        var top = open.OrderBy(f => f.Severity).First(); // Critical < High < Medium < Low

        var hasCritical = open.Any(f => f.Severity == FindingSeverity.Critical);
        var hasHigh = open.Any(f => f.Severity == FindingSeverity.High);

        var score = hasCritical ? 40 : hasHigh ? 65 : 85;
        var status = hasCritical ? "Critical" : hasHigh ? "Warning" : "Pass";

        var steps = top.RemediationSteps?
            .Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .ToList() ?? [];

        return new SubScoreItem(
            Score: score,
            Status: status,
            Title: top.Title,
            Explanation: top.AiExplanation ?? "No explanation available.",
            RemediationSteps: steps,
            TechnicalDetail: DeserializePayload(top.TechnicalPayload)
        );
    }

    private static string ClassifyRisk(int? score) => score switch
    {
        >= 80 => "safe",
        >= 60 => "moderate",
        _ => "at_risk"
    };

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
}



// Expand finding items in summary
public record FindingItemDto(
    Guid Id,
    string Surface,
    string Severity,
    string Title,
    string? CveId,
    string Explanation,
    List<string> RemediationSteps,
    object? TechnicalDetail,
    FindingStatus Status
);
