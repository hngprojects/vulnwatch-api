using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using StackExchange.Redis;
using Web.Hubs;

namespace Web.Consumers;

public record DomainIntel(
    Guid ScanId,
    Guid DomainId,
    string DomainName,
    Guid RequestedBy,
    int SecurityScore,
    string Status,
    DateTimeOffset CompletedAt,
    string? Error);

public class DomainIntelConsumer : BackgroundService
{
    private const string Queue = "domain-intel";
    private readonly IConnectionMultiplexer _redis;
    private readonly IHubContext<ScanHub> _hub;
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<DomainIntelConsumer> _logger;

    public DomainIntelConsumer(
        IConnectionMultiplexer redis,
        IHubContext<ScanHub> hub,
        IServiceScopeFactory scopeFactory,
        ILogger<DomainIntelConsumer> logger)
    {
        _redis = redis;
        _hub = hub;
        _scopeFactory = scopeFactory;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        _logger.LogInformation("DomainIntelConsumer listening on {Queue}", Queue);
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

                // var raw = result.ToString();
                // _logger.LogInformation("Domain Raw scan result payload: {Payload}", raw);
                
                // Console.WriteLine(raw);
                // Console.WriteLine(raw[0]);

                var json = JsonSerializer.Deserialize<string>(result.ToString());

                var message = JsonSerializer.Deserialize<DomainIntel>(
                    json!,
                    new JsonSerializerOptions
                    {
                        PropertyNameCaseInsensitive = true
                    });

                if (message is null) continue;

                _logger.LogInformation("Scan result received for {ScanId}", message.ScanId);

                //1. emit to the user's webhook
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