
using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Microsoft.AspNetCore.Identity;

namespace Web.Workers;

public class AlertOutboxProcessor : BackgroundService
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ILogger<AlertOutboxProcessor> _logger;
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(15);

    public AlertOutboxProcessor(
        IServiceScopeFactory scopeFactory,
        ILogger<AlertOutboxProcessor> logger)
    {
        _scopeFactory = scopeFactory;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await ProcessBatch(ct);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Alert outbox processor error");
            }

            await Task.Delay(Interval, ct);
        }
    }

    private async Task ProcessBatch(CancellationToken ct)
    {
        using var scope = _scopeFactory.CreateScope();
        var alerts = scope.ServiceProvider.GetRequiredService<IAlertRepository>();
        var email = scope.ServiceProvider.GetRequiredService<IEmailService>();

        var pending = await alerts.GetPendingAsync(batchSize: 50, ct);

        foreach (var alert in pending)
        {
            try
            {
                switch (alert.Channel)
                {
                    case AlertChannel.Email:
                        await email.SendAsync(
                            to: await ResolveEmail(scope, alert.UserId, ct),
                            subject: alert.Subject,
                            body: alert.Body);
                        break;

                    case AlertChannel.Slack:
                        // wire up your Slack webhook here
                        _logger.LogInformation("Slack alert stub for user {UserId}", alert.UserId);
                        break;

                    case AlertChannel.Push:
                        _logger.LogInformation("Push alert stub for user {UserId}", alert.UserId);
                        break;
                }

                alert.MarkSent();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Failed to send alert {AlertId}", alert.Id);
                alert.MarkFailed(ex.Message);
            }
        }

        await alerts.SaveChangesAsync(ct);
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