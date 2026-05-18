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

                //1. emit to the user's webhook
                await _hub.Clients
                    .Group($"user:{message.RequestedBy}")
                    .SendAsync("ScanCompleted", message, ct);

                if (!Guid.TryParse(message.ScanId, out var scanId) ||
                 !Guid.TryParse(message.DomainId, out var domainId) ||
                 !Guid.TryParse(message.RequestedBy, out var userId))
                {
                    _logger.LogWarning("Invalid scan-result payload IDs. ScanId={ScanId}", message.ScanId);
                    continue;
                }

                var severities = new List<FindingSeverity>();
                foreach (var finding in message.Findings)
                {
                    if (!Enum.TryParse<FindingSeverity>(finding.Severity, ignoreCase: true, out var sev))
                    {
                        _logger.LogWarning("Invalid finding severity '{Severity}' for scan {ScanId}", finding.Severity, scanId);
                        continue;
                    }
                    severities.Add(sev);
                }

                //2. Dispatch Alerts
                using var scope = _scopeFactory.CreateScope();
                var dispatcher = scope.ServiceProvider.GetRequiredService<AlertDispatcher>();

                await dispatcher.DispatchAsync(new ScanCompletedEvent(
                    ScanId: scanId,
                    DomainId: domainId,
                    UserId: userId,
                    DomainName: message.DomainName,
                    SecurityScore: message.SecurityScore,
                   FindingSeverities: severities), ct);

            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                _logger.LogError(ex, "Error processing scan result");
                await Task.Delay(1000, ct);
            }
        }
    }
}