

using Domain.Enums;

namespace Application.Features.Scans.DTOs;

public record ScanReportDto(
    Guid ScanId,
    Guid DomainId,
    string DomainName,
    VerificationStatus DomainStatus,
    ScanStatus Status,
    ScanCoverage Coverage,
    int? SecurityScore,
    string? RiskLevel,           // "at_risk" | "moderate" | "safe"
    DateTime? CompletedAt,
    ScanSummaryDto Summary,
    FindingGroupsDto FindingGroups,
    SubScoresDto SubScores
);

public record ScanSummaryDto(
    List<string> CriticalIssues,
    List<string> HighSeverityIssues,
    string? GoodNews
);

public record FindingGroupsDto(
    int CriticalCount,
    int HighCount,
    int MediumCount,
    int LowCount,
    int PassCount
);

public record SubScoresDto(
    SubScoreItem Exposure,
    SubScoreItem Ssl,
    SubScoreItem Dns
);

public record SubScoreItem(
    int Score,
    string Status,    // "Critical" | "Pass" | "Warning"
    string? Detail
);