using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using StackExchange.Redis;
using Web.Hubs;
using Domain.Entities;
using Domain.Enums;
using Application.Features.Alerts;
using Application.Interfaces;
using Microsoft.AspNetCore.Identity;
using Domain.Events;

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
                var result = await db.ListRightPopAsync(Queue);

                if (result.IsNullOrEmpty)
                {
                    await Task.Delay(500, ct);
                    continue;
                }

                var json = JsonSerializer.Deserialize<string>(result.ToString());
                var message = JsonSerializer.Deserialize<DomainIntel>(
                    json!, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });

                if (message is null)
                {
                    _logger.LogWarning("DomainIntelConsumer received null message — skipping");
                    continue;
                }

                // 1. Push real-time update to the connected frontend client
                await _hub.Clients
                    .Group($"user:{message.RequestedBy}")
                    .SendAsync("ScanCompleted", message, ct);

                // 2. Dispatch alerts — only if the scan actually completed
                if (string.Equals(message.Status, "completed", StringComparison.OrdinalIgnoreCase))
                    await DispatchScanAlertsAsync(message, ct);
                else
                    _logger.LogInformation(
                        "Scan {ScanId} status is {Status} — skipping alert dispatch",
                        message.ScanId, message.Status);
            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                _logger.LogError(ex, "Error processing domain intel message");
                await Task.Delay(1000, ct);
            }
        }
    }

    private async Task DispatchScanAlertsAsync(DomainIntel message, CancellationToken ct)
    {
        if (!Guid.TryParse(message.ScanId.ToString(), out var scanId) ||
            !Guid.TryParse(message.DomainId.ToString(), out var domainId) ||
            !Guid.TryParse(message.RequestedBy.ToString(), out var userId))
        {
            _logger.LogWarning(
                "DomainIntelConsumer — invalid IDs in message, cannot dispatch alerts. " +
                "ScanId: {ScanId}, DomainId: {DomainId}, RequestedBy: {RequestedBy}",
                message.ScanId, message.DomainId, message.RequestedBy);
            return;
        }

        using var scope = _scopeFactory.CreateScope();
        var scanRepo = scope.ServiceProvider.GetRequiredService<IScanRepository>();
        var dispatcher = scope.ServiceProvider.GetRequiredService<AlertDispatcher>();
        var alertRepo = scope.ServiceProvider.GetRequiredService<IAlertRepository>();
        var slackRepo = scope.ServiceProvider.GetRequiredService<ISlackIntegrationRepository>();
        var slackService = scope.ServiceProvider.GetRequiredService<ISlackService>();
        var emailService = scope.ServiceProvider.GetRequiredService<IEmailService>();
        var userManager = scope.ServiceProvider.GetRequiredService<UserManager<User>>();

        var scan = await scanRepo.FindByIdWithFindings(scanId, ct);

        if (scan is null || scan.Status != ScanStatus.Completed)
        {
            return;
        }

        var findingSeverities = scan.Findings
            .Where(f => f.Status == FindingStatus.Open)
            .Select(f => f.Severity)
            .ToList();

        await dispatcher.DispatchAsync(new ScanCompletedEvent(
            ScanId: scanId,
            DomainId: domainId,
            UserId: userId,
            DomainName: message.DomainName,
            SecurityScore: scan.SecurityScore ?? 0,
            FindingSeverities: findingSeverities), ct);

        await FlushPendingAlertsAsync(
            scope, alertRepo, emailService, slackRepo, slackService, userManager, userId, ct);
    }

    // Flush only this user's alerts immediately after a scan completes —
    // the AlertOutboxProcessor will handle everything else on its normal cadence
    private async Task FlushPendingAlertsAsync(
        IServiceScope scope,
        IAlertRepository alertRepo,
        IEmailService emailService,
        ISlackIntegrationRepository slackRepo,
        ISlackService slackService,
        UserManager<User> userManager,
        Guid userId,
        CancellationToken ct)
    {
        var pending = await alertRepo.GetPendingByUser(userId, batchSize: 20, ct);

        if (pending.Count == 0)
        {
            return;
        }

        var slackIntegration = await slackRepo.GetActiveByUserId(userId, ct);
        var user = await userManager.FindByIdAsync(userId.ToString());

        foreach (var alert in pending)
        {
            try
            {
                switch (alert.Channel)
                {
                    case AlertChannel.Email:
                        await DeliverEmail(scope, emailService, alert, ct);
                        break;

                    case AlertChannel.Slack:
                        await DeliverSlack(slackRepo, slackService, alert, ct);
                        break;

                    default:
                        alert.MarkFailed($"Channel {alert.Channel} not implemented.");
                        break;
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex,
                    "Failed to flush alert {AlertId} via {Channel}",
                    alert.Id, alert.Channel);
                alert.MarkFailed(ex.Message);
            }
        }

        await alertRepo.SaveChangesAsync(ct);
    }

    private async Task DeliverEmail(
        IServiceScope scope,
        IEmailService email,
        Alert alert,
        CancellationToken ct)
    {
        var to = await ResolveEmail(scope, alert.UserId, ct);

        if (to is null)
        {
            alert.MarkFailed("User email not found.");
            return;
        }

        await email.SendAsync(to, alert.Subject, alert.Body);
        alert.MarkSent();
    }

     private async Task DeliverSlack(
        ISlackIntegrationRepository slackRepo,
        ISlackService slackService,
        Alert alert,
        CancellationToken ct)
    {

        var integration = await slackRepo.GetActiveByUserId(alert.UserId, ct);

        if (integration is null)
        {
            alert.MarkFailed("No active Slack integration.");
            return;
        }

        await slackService.SendMessageAsync(
            integration.BotAccessToken,
            integration.ChannelId,
            alert.Subject,
            BuildSlackBlocks(alert),
            ct);

        alert.MarkSent();
    }

    private static object BuildSlackBlocks(Alert alert)
    {
        var (emoji, _) = alert.Severity switch
        {
            AlertSeverity.Critical => ("🔴", "#E53935"),
            AlertSeverity.Warning => ("🟡", "#FB8C00"),
            _ => ("🔵", "#1E88E5")
        };

        var typeLabel = alert.Type switch
        {
            AlertType.SslExpiry => "SSL Certificate Expiry",
            AlertType.ScanCompleted => "Scan Completed",
            AlertType.CriticalFindingDetected => "Critical Finding",
            AlertType.SecurityScoreDrop => "Security Score Drop",
            AlertType.DomainStatusChanged => "Domain Status Changed",
            _ => alert.Type.ToString()
        };

        var severityColor = alert.Severity switch
        {
            AlertSeverity.Critical => "danger",
            AlertSeverity.Warning => "warning",
            _ => "good"
        };

        // Use object[] — each block has a different shape so anonymous arrays won't compile
        var blocks = new object[]
        {
            new
            {
                type = "header",
                text = new { type = "plain_text", text = $"{emoji} {alert.Subject}", emoji = true }
            },
            new
            {
                type = "section",
                fields = new object[]
                {
                    new { type = "mrkdwn", text = $"*Alert Type:*\n{typeLabel}" },
                    new { type = "mrkdwn", text = $"*Severity:*\n{alert.Severity}" },
                }
            },
            new { type = "divider" },
            new
            {
                type = "context",
                elements = new object[]
                {
                    new
                    {
                        type = "mrkdwn",
                        text = $"🔐 *VulnWatch* · {alert.CreatedAt:MMM d, yyyy HH:mm} UTC"
                    }
                }
            }
        };

        return blocks;
    }

    private static async Task<string> ResolveEmail(
        IServiceScope scope, Guid userId, CancellationToken ct)
    {
        var userManager = scope.ServiceProvider
            .GetRequiredService<UserManager<User>>();
        var user = await userManager.FindByIdAsync(userId.ToString());
        return user?.Email ?? throw new InvalidOperationException(
            $"No email for user {userId}");
    }
}