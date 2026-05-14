using Domain.Enums;

namespace Domain.Entities;

public class Integration : EntityBase
{
    public Guid UserId { get; private set; }
    public string Provider { get; private set; } = default!;
    public string InstallationId { get; private set; } = default!;
    public IntegrationStatus Status { get; private set; }

    public User User { get; private set; } = default!;

    private Integration() { }

    public static Integration Create(Guid userId, string provider, string installationId)
        => new()
        {
            UserId = userId,
            Provider = provider,
            InstallationId = installationId,
            Status = IntegrationStatus.INACTIVE,
        };

    public void Activate()
    {
        Status = IntegrationStatus.ACTIVE;
        Touch();
    }

    public void Deactivate()
    {
        Status = IntegrationStatus.INACTIVE;
        Touch();
    }
}
