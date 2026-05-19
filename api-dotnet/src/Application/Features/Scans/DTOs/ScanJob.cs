

namespace Application.Features.Scans.DTOs;

// Application/Features/Scans/DTOs/ScanJob.cs
// public record ScanJob(Guid DomainId, Guid ScanId);
public record ScanJob(Guid DomainId, string DomainName, Guid ScanId, string ScanType, Guid RequestedBy, DateTime EnqueuedAt)
{
    public static ScanJob Create(Guid domainId, string domainName, Guid scanId, string scanType, Guid requestedBy, DateTime enqueuedAt) => new(domainId, domainName, scanId, scanType, requestedBy, enqueuedAt);
}
