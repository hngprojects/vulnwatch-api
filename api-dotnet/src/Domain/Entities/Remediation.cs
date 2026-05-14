using Domain.Enums;

namespace Domain.Entities;

public class Remediation : EntityBase
{
    public Guid FindingId { get; private set; }
    public int? PrNumber { get; private set; }
    public string? PrUrl { get; private set; }
    public RemediationStatus Status { get; private set; }

    public Finding Finding { get; private set; } = default!;

    private Remediation() { }

    public static Remediation Create(Guid findingId)
        => new()
        {
            FindingId = findingId,
            Status = RemediationStatus.Open,
        };

    public void AttachPr(int prNumber, string prUrl)
    {
        PrNumber = prNumber;
        PrUrl = prUrl;
        Touch();
    }

    public void SetStatus(RemediationStatus status)
    {
        Status = status;
        Touch();
    }
}
