

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
    List<FindingItemDto> CriticalIssues,
    List<FindingItemDto> HighSeverityIssues,
    List<FindingItemDto> MediumSeverityIssues,
    List<FindingItemDto> LowSeverityIssues,
    string GoodNews,
    string? TopRecommendation,
    string Headline
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
    string Status,
    string Title,
    string Explanation,
    List<string> RemediationSteps,
    object? TechnicalDetail  // deserialized from TechnicalPayload JSON
);