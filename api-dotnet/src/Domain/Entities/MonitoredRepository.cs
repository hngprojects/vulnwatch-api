namespace Domain.Entities;

public class MonitoredRepository : EntityBase
{
    public long RepoId { get; private set; }
    public Guid UserId { get; private set; }
    public string FullName { get; private set; } = default!;
    public bool IsMonitoringActive { get; private set; }
    public string DefaultBranch { get; private set; } = default!;
    public long GitHubInstallationId { get; private set; }

    public User User { get; private set; } = default!;
    public ICollection<Scan> Scans { get; private set; } = new List<Scan>();

    private MonitoredRepository() { }

    public static MonitoredRepository Create(long repoId, Guid userId, string fullName, long gitHubInstallationId, string defaultBranch = "main")
        => new()
        {
            RepoId = repoId,
            UserId = userId,
            FullName = fullName,
            GitHubInstallationId = gitHubInstallationId,
            DefaultBranch = defaultBranch,
            IsMonitoringActive = true,
        };

    public void Deactivate()
    {
        IsMonitoringActive = false;
        Touch();
    }

    public void Activate()
    {
        IsMonitoringActive = true;
        Touch();
    }
}
