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
    private static readonly TimeSpan SlackStateTtl = TimeSpan.FromMinutes(10);

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

    public async Task SaveSlackState(string state, Guid userId, CancellationToken ct)
    {
        var db = _redis.GetDatabase();
        await db.StringSetAsync(
            SlackStateKey(state),
            userId.ToString(),
            SlackStateTtl);
    }

    public async Task<Guid?> ValidateSlackState(string state, CancellationToken ct)
    {
        var db = _redis.GetDatabase();
        var key = SlackStateKey(state);

        // Atomic get-and-delete — prevents replay attacks
        var value = await db.StringGetDeleteAsync(key);

        if (value.IsNullOrEmpty)
            return null;

        return Guid.TryParse(value, out var userId) ? userId : null;
    }

    private static string SlackStateKey(string state) => $"slack:oauth:state:{state}";

}