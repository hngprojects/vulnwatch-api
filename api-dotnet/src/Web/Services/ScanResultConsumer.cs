using System.Text.Json;
using Application.Interfaces;
using Microsoft.AspNetCore.SignalR;
using StackExchange.Redis;
using Web.Hubs;

namespace Web.Services;

public record ScanResultMessage(
    string ScanId,
    string DomainId,
    string DomainName,
    string RequestedBy,
    int SecurityScore,
    List<FindingMessage> Findings);

public record FindingMessage(
    string Surface,
    string Severity,
    string Title,
    string? CveId,
    string AiExplanation,
    string TechnicalPayload,
    string RemediationSteps);

public class ScanResultConsumer : BackgroundService
{
    private const string Queue = "scan-results";
    private readonly IConnectionMultiplexer _redis;
    private readonly IHubContext<ScanHub> _hub;
    private readonly ILogger<ScanResultConsumer> _logger;

    public ScanResultConsumer(
        IConnectionMultiplexer redis,
        IHubContext<ScanHub> hub,
        ILogger<ScanResultConsumer> logger)
    {
        _redis = redis;
        _hub = hub;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        _logger.LogInformation("ScanResultConsumer listening on {Queue}", Queue);
        var db = _redis.GetDatabase();

        while (!ct.IsCancellationRequested)
        {
            try
            {
                // BLPOP equivalent — poll with timeout
                var result = await db.ListRightPopAsync(Queue);

                if (result.IsNullOrEmpty)
                {
                    await Task.Delay(500, ct);
                    continue;
                }

                var message = JsonSerializer.Deserialize<ScanResultMessage>(
                    result!, new JsonSerializerOptions
                    {
                        PropertyNameCaseInsensitive = true
                    });

                if (message is null) continue;

                _logger.LogInformation("Scan result received for {ScanId}", message.ScanId);

                // emit to the user's SignalR group
                await _hub.Clients
                    .Group($"user:{message.RequestedBy}")
                    .SendAsync("ScanCompleted", message, ct);
            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                _logger.LogError(ex, "Error processing scan result");
                await Task.Delay(1000, ct);
            }
        }
    }
}