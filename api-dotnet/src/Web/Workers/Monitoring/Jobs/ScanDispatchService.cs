using Application.Features.Scans.DTOs;
using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Microsoft.Extensions.Logging;

namespace Web.Workers.Monitoring;

public sealed class ScanDispatchService(
    IScanRepository scanRepo,
    IRedisService redis,
    ILogger<ScanDispatchService> logger)
{
    // Returns true if a scan was enqueued, false if one was already running
    public async Task<bool> DispatchAsync(
        DomainSettings settings,
        CancellationToken ct)
    {
        var domainId   = settings.DomainId;
        var domainName = settings.Domain.DomainName;

        // Guard — never queue if a scan is already in flight
        var running = await scanRepo.FindRunningByDomain(domainId, ct);
        if (running is not null)
        {
            logger.LogDebug(
                "Skipping dispatch for {Domain} — scan {ScanId} already running",
                domainName, running.Id);
            return false;
        }

        var idempotencyKey = Guid.NewGuid();

        var scan = Domain.Entities.Scan.Create(
            userId:         settings.Domain.UserId,
            idempotencyKey: idempotencyKey,
            targetType:     ScanTargetType.Domain,
            coverage:       ScanCoverage.Full,
            surfaceTypes:   SurfaceType.Dns | SurfaceType.Ssl | SurfaceType.Http,
            domainId:       domainId);

        await scanRepo.AddAsync(scan, ct);
        await scanRepo.SaveChangesAsync(ct);

        await redis.PublishScanJob("scan-jobs", new ScanJob(
            domainId,
            domainName,
            scan.Id,
            ScanTargetType.Domain.ToString(),
            scan.SurfaceTypes.ToString(),
            settings.Domain.UserId,
            scan.CreatedAt), ct);

        logger.LogInformation(
            "Monitoring scan dispatched for {Domain} — scan {ScanId}",
            domainName, scan.Id);

        return true;
    }
}