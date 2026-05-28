using Application.Features.Scans.DTOs;

namespace Application.Interfaces;

public interface IRedisService
{
    // Task PublishAsync(ScanJob job, CancellationToken ct = default);
    Task PublishScanJob(string queue, ScanJob job, CancellationToken ct = default);
    Task SaveSlackState(string state, Guid userId, CancellationToken ct);
    Task<Guid?> ValidateSlackState(string state, CancellationToken ct);
}