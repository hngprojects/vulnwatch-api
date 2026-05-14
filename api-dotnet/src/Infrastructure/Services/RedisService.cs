using System.Text.Json;
using Application.Features.Scans.DTOs;
using Application.Interfaces;
using Microsoft.Extensions.Logging;
using StackExchange.Redis;

namespace Infrastructure.Services;

public class RedisService : IRedisService
{
    private readonly ILogger<RedisService> _logger;
    private readonly IConnectionMultiplexer _redis;


    public RedisService(ILogger<RedisService> logger, IConnectionMultiplexer redis)
    {
        _logger = logger;
        _redis = redis;
    }

    public async Task PublishScanJob(string queueKey, ScanJob job, CancellationToken ct = default)
    {
        var db = _redis.GetDatabase();
        var payload = JsonSerializer.Serialize(job);

        await db.ListLeftPushAsync(queueKey, payload);

        _logger.LogInformation("Scan job published for domain {DomainId}, scan {ScanId}",
            job.DomainId, job.ScanId);
    }

}