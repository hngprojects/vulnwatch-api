
using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Domain.Meta;
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
        var integrations    = scope.ServiceProvider.GetRequiredService<IIntegrationRepository>();
        var slackService = scope.ServiceProvider.GetRequiredService<ISlackService>();

        var pending = await alerts.GetPendingAsync(batchSize: BatchSize, ct);

        if (pending.Count == 0)
            return 0;

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
                        await DeliverSlackAsync(integrations, slackService, alert, ct);
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
                _logger.LogError(ex, "Failed to send alert {AlertId}", alert.Id);
                alert.MarkFailed("Delivery failed. See logs for details.");
            }
        }

        await alerts.SaveChangesAsync(ct);
        return pending.Count;
    }

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

    private static async Task DeliverSlackAsync(
        IIntegrationRepository integrations,
        ISlackService slackService,
        Alert alert,
        CancellationToken ct)
    {
        var integration = await integrations.GetByUserAndProvider(alert.UserId, IntegrationProvider.Slack, ct);

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