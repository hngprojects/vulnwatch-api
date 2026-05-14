using Domain.Enums;

namespace Domain.Entities;

public class Finding : EntityBase
{
    public Guid ScanId { get; private set; }
    public FindingSurface Surface { get; private set; }
    public FindingSeverity Severity { get; private set; }
    public string Title { get; private set; } = default!;
    public string? CveId { get; private set; }
    public string? AiExplanation { get; private set; }
    public string? TechnicalPayload { get; private set; }
    public string? RemediationSteps { get; private set; }
    public FindingStatus Status { get; private set; }

    public Scan Scan { get; private set; } = default!;

    private Finding() { }

    public static Finding Create(Guid scanId, FindingSurface surface, FindingSeverity severity, string title,
        string? cveId = null, string? aiExplanation = null, string? technicalPayload = null, string? remediationSteps = null)
        => new()
        {
            ScanId = scanId,
            Surface = surface,
            Severity = severity,
            Title = title,
            CveId = cveId,
            AiExplanation = aiExplanation,
            TechnicalPayload = technicalPayload,
            RemediationSteps = remediationSteps,
            Status = FindingStatus.Open,
        };

    public void Remediate()
    {
        Status = FindingStatus.Remediated;
        Touch();
    }

    public void Ignore()
    {
        Status = FindingStatus.Ignored;
        Touch();
    }
}
