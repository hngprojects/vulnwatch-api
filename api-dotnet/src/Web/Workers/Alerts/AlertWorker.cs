
using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Microsoft.AspNetCore.Identity;

namespace Web.Workers.Alerts;

public class AlertOutboxProcessor : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<AlertOutboxProcessor> _logger;
    private static readonly TimeSpan IdleInterval  = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan BusyInterval  = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan MinInterval   = TimeSpan.FromSeconds(10);
    private const int BatchSize = 50;

    public AlertOutboxProcessor(
        IServiceScopeFactory scopeFactory,
        ILogger<AlertOutboxProcessor> logger)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        await Task.Delay(TimeSpan.FromSeconds(15), ct);

        while (!ct.IsCancellationRequested)
        {
            int processed = 0;

            try
            {
                processed = await ProcessBatch(ct);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Alert outbox processor error");
            }

            // Back off when idle, stay responsive when there's work
            var delay = processed == 0         ? IdleInterval
                      : processed >= BatchSize ? MinInterval   // batch was full — likely more queued
                      :                          BusyInterval;

            _logger.LogDebug(
                "Alert outbox processed {Count} alert(s) — next tick in {Delay}",
                processed, delay);

            await Task.Delay(delay, ct);
        }
    }

    private async Task<int> ProcessBatch(CancellationToken ct)
    {
        using var scope = _scopeFactory.CreateScope();

        var alerts       = scope.ServiceProvider.GetRequiredService<IAlertRepository>();
        var email        = scope.ServiceProvider.GetRequiredService<IEmailService>();
        var slackRepo    = scope.ServiceProvider.GetRequiredService<ISlackIntegrationRepository>();
        var slackService = scope.ServiceProvider.GetRequiredService<ISlackService>();

        var pending = await alerts.GetPendingAsync(batchSize: BatchSize, ct);

        if (pending.Count == 0)
            return 0;

        _logger.LogInformation(
            "Alert outbox processing {Count} pending alert(s)", pending.Count);

        foreach (var alert in pending)
        {
            try
            {
                switch (alert.Channel)
                {
                    case AlertChannel.Email:
                        await DeliverEmailAsync(scope, email, alert, ct);
                        break;

                    case AlertChannel.Slack:
                        await DeliverSlackAsync(slackRepo, slackService, alert, ct);
                        break;

                    default:
                        _logger.LogWarning(
                            "Unhandled alert channel {Channel} for alert {AlertId}",
                            alert.Channel, alert.Id);
                        alert.MarkFailed($"Channel {alert.Channel} not implemented.");
                        break;
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Failed to deliver alert {AlertId} via {Channel}",
                    alert.Id, alert.Channel);
                alert.MarkFailed(ex.Message);
            }
        }

        await alerts.SaveChangesAsync(ct);
        return pending.Count;
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    private static async Task DeliverEmailAsync(
        IServiceScope scope,
        IEmailService email,
        Alert alert,
        CancellationToken ct)
    {
        var to = await ResolveEmail(scope, alert.UserId, ct);

        await email.SendAsync(to, alert.Subject, alert.Body);
        alert.MarkSent();
    }

    // ── Slack ─────────────────────────────────────────────────────────────────

    private static async Task DeliverSlackAsync(
        ISlackIntegrationRepository slackRepo,
        ISlackService slackService,
        Alert alert,
        CancellationToken ct)
    {
        // _logger.LogInformation(
        //     "Delivering Slack alert {AlertId} for user {UserId} — type: {Type}, severity: {Severity}",
        //     alert.Id, alert.UserId, alert.Type, alert.Severity);

        var integration = await slackRepo.GetActiveByUserId(alert.UserId, ct);

        if (integration is null)
        {
            // _logger.LogWarning(
            //     "No active Slack integration found for user {UserId} — alert {AlertId} cannot be delivered",
            //     alert.UserId, alert.Id);
            alert.MarkFailed("No active Slack integration.");
            return;
        }

        // _logger.LogInformation(
        //     "Sending Slack alert {AlertId} to channel {Channel} in team {Team}",
        //     alert.Id, integration.ChannelName, integration.TeamName);

        await slackService.SendMessageAsync(
            integration.BotAccessToken,
            integration.ChannelId,
            alert.Subject,
            BuildSlackBlocks(alert),
            ct);

        alert.MarkSent();

        // _logger.LogInformation(
        //     "Slack alert {AlertId} delivered to {Channel}", alert.Id, integration.ChannelName);
    }

    // ── Block Kit message ─────────────────────────────────────────────────────

    private static object BuildSlackBlocks(Alert alert)
    {
        var (emoji, _) = alert.Severity switch
        {
            AlertSeverity.Critical => ("🔴", "#E53935"),
            AlertSeverity.Warning  => ("🟡", "#FB8C00"),
            _                      => ("🔵", "#1E88E5")
        };

        var typeLabel = alert.Type switch
        {
            AlertType.SslExpiry               => "SSL Certificate Expiry",
            AlertType.ScanCompleted           => "Scan Completed",
            AlertType.CriticalFindingDetected => "Critical Finding",
            AlertType.SecurityScoreDrop       => "Security Score Drop",
            AlertType.DomainStatusChanged     => "Domain Status Changed",
            _                                 => alert.Type.ToString()
        };

        var severityColor = alert.Severity switch
        {
            AlertSeverity.Critical => "danger",
            AlertSeverity.Warning  => "warning",
            _                      => "good"
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
                        text = $"*VulnWatch* · {alert.CreatedAt:MMM d, yyyy HH:mm} UTC"
                    }
                }
            }
        };

        return blocks;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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