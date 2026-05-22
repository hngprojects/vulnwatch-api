using Domain.Enums;

namespace Domain.Entities;

public class RepositoryEcosystem : EntityBase
{
    public Guid ScanId { get; set; }
    public RepositoryIntel Scan { get; set; } = null!;

    public string Ecosystem { get; set; } = null!;

    public int TotalDeps { get; set; }

    public int VulnerableCount { get; set; }
}