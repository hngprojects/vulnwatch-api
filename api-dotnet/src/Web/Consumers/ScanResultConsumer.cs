using System.Text.Json;
using Application.Interfaces;
using Application.Services;
using Domain.Enums;
using Domain.Events;
using Microsoft.AspNetCore.SignalR;
using StackExchange.Redis;
using Web.Hubs;

namespace Web.Consumers;

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
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<ScanResultConsumer> _logger;

    public ScanResultConsumer(
        IConnectionMultiplexer redis,
        IHubContext<ScanHub> hub,
        IServiceScopeFactory scopeFactory,
        ILogger<ScanResultConsumer> logger)
    {
        _redis = redis;
        _hub = hub;
        _scopeFactory = scopeFactory;
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

                using var scope = _scopeFactory.CreateScope();
                var dispatcher = scope.ServiceProvider.GetRequiredService<AlertDispatcher>();

                await dispatcher.DispatchAsync(new ScanCompletedEvent(
                    ScanId: Guid.Parse(message.ScanId),
                    DomainId: Guid.Parse(message.DomainId),
                    UserId: Guid.Parse(message.RequestedBy),
                    DomainName: message.DomainName,
                    SecurityScore: message.SecurityScore,
                    FindingSeverities: message.Findings
                        .Select(f => Enum.Parse<FindingSeverity>(f.Severity))
                        .ToList()), ct);
            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                _logger.LogError(ex, "Error processing scan result");
                await Task.Delay(1000, ct);
            }
        }
    }
}