using System.ComponentModel.DataAnnotations.Schema;
using Domain.Enums;

namespace Domain.Entities;

public class RepositoryIntel : EntityBase
{
    [Column("scan_id")]
    public Guid ScanId { get; set; }

    [Column("repo_id")]
    public Guid RepoId { get; set; }

    [Column("requested_by")]
    public string? RequestedBy { get; set; }

    [Column("ecosystem")]
    public string? Ecosystem { get; set; }

    [Column("total_deps")]
    public int TotalDeps { get; set; }

    [Column("vulnerable_count")]
    public int VulnerableCount { get; set; }

    [Column("overall_severity")]
    public string? OverallSeverity { get; set; }

    [Column("completed_at")]
    public DateTimeOffset? CompletedAt { get; set; }

    [Column("raw_result", TypeName = "jsonb")]
    public string? RawResult { get; set; }

    private RepositoryIntel() { }

    public static RepositoryIntel Create(
        Guid scanId,
        Guid repoId,
        string? requestedBy = null,
        string? ecosystem = null)
    {
        return new RepositoryIntel
        {
            ScanId = scanId,
            RepoId = repoId,
            RequestedBy = requestedBy,
            Ecosystem = ecosystem,
            TotalDeps = 0,
            VulnerableCount = 0,
            OverallSeverity = null,
            CompletedAt = null,
            RawResult = null
        };
    }

    public void Complete(
        int totalDeps,
        int vulnerableCount,
        string? overallSeverity,
        string? rawResult)
    {
        TotalDeps = totalDeps;
        VulnerableCount = vulnerableCount;
        OverallSeverity = overallSeverity;
        RawResult = rawResult;
        CompletedAt = DateTimeOffset.UtcNow;
    }
}