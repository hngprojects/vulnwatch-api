using System.Text.Json;
using Application.Features.Alerts;
using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Domain.Events;
using Domain.Meta;
using Microsoft.AspNetCore.Identity;
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

                //2. dispatch alerts
                await DispatchScanAlerts(message, ct);


            }
            catch (Exception ex) when (!ct.IsCancellationRequested)
            {
                _logger.LogError(ex, "Error processing scan result");
                await Task.Delay(1000, ct);
            }
        }
    }

    private async Task DispatchScanAlerts(DomainIntel message, CancellationToken ct)
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

        // _logger.LogInformation(
        //     "Dispatching alerts for Scan: {ScanMessage}",
        //     message);

        using var scope = _scopeFactory.CreateScope();
        var scanRepo = scope.ServiceProvider.GetRequiredService<IScanRepository>();
        var dispatcher = scope.ServiceProvider.GetRequiredService<AlertDispatcher>();
        var alertRepo = scope.ServiceProvider.GetRequiredService<IAlertRepository>();
        var integrationsRepo = scope.ServiceProvider.GetRequiredService<IIntegrationRepository>();
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
            scope, alertRepo, emailService, integrationsRepo, slackService, userManager, userId, ct);
    }

    // Flush only this user's alerts immediately after a scan completes —
    // the AlertOutboxProcessor will handle everything else on its normal cadence
    private async Task FlushPendingAlertsAsync(
        IServiceScope scope,
        IAlertRepository alertRepo,
        IEmailService emailService,
        IIntegrationRepository integrationsRepo,
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
                        await DeliverSlack(integrationsRepo, slackService, alert, ct);
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
    IIntegrationRepository integrationsRepo,
    ISlackService slackService,
    Alert alert,
    CancellationToken ct)
    {
        

        var integration = await integrationsRepo.GetByUserAndProvider(
            alert.UserId, IntegrationProvider.Slack, ct);


        if (integration is null)
        {
            alert.MarkFailed("No active Slack integration.");
            return;
        }

        var webhookUrl = integration.GetMetadata(SlackMetadataKeys.WebhookUrl);

        if (string.IsNullOrWhiteSpace(webhookUrl))
        {
            alert.MarkFailed("Slack webhook URL not found.");
            return;
        }

        await slackService.SendMessageViaWebhookUrl(webhookUrl, alert.Subject, BuildSlackBlocks(alert), ct);
        alert.MarkSent();
    }


    private static object BuildSlackBlocks(Alert alert)
    {
        var completedAt = alert.CreatedAt.ToString("MMM d, yyyy 'at' HH:mm 'UTC'");

        var blocks = new object[]
        {
            new
            {
                type = "section",
                text = new
                {
                    type = "mrkdwn",
                    text = "*Scan completed successfully*"
                }
            },

            new
            {
                type = "divider"
            },

            // Main details
            new
            {
                type = "section",
                fields = new object[]
                {
                    new
                    {
                        type = "mrkdwn",
                        text = $"`{alert.Subject}`"
                    },
                    new
                    {
                        type = "mrkdwn",
                        text = $"*Status*\nCompleted"
                    },
                    new
                    {
                        type = "mrkdwn",
                        text = $"*Scan Type*\nFull Security Scan"
                    },
                    new
                    {
                        type = "mrkdwn",
                        text = $"*Completed At*\n{completedAt}"
                    }
                }
            },

            // Summary section
            new
            {
                type = "section",
                text = new
                {
                    type = "mrkdwn",
                    text =
                        "*Summary*\n" +
                        "The scheduled vulnerability assessment finished successfully. " +
                        "Review findings and remediation recommendations in the dashboard."
                }
            },

            new
            {
                type = "divider"
            },

            // Footer context
            new
            {
                type = "context",
                elements = new object[]
                {
                    new
                    {
                        type = "mrkdwn",
                        text = "VulnWatch Security Monitoring"
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