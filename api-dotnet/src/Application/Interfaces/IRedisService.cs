using Application.Features.Scans.DTOs;

namespace Application.Interfaces;

public interface IRedisService
{
    // Task PublishAsync(ScanJob job, CancellationToken ct = default);
    Task PublishScanJob(string queue, ScanJob job, CancellationToken ct = default);
}