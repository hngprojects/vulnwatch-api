using Application.Features.Alerts.SslExpiry;
using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Domain.Events;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;

namespace Application.Features.Alerts;

public class AlertDispatcher
{
    private readonly IAlertRepository _alerts;
    private readonly INotificationPreferencesRepository _prefs;
    private readonly ILogger<AlertDispatcher> _logger;

    public AlertDispatcher(
        IAlertRepository alerts,
        INotificationPreferencesRepository prefs,
        ILogger<AlertDispatcher> logger)
    {
        _alerts = alerts;
        _prefs = prefs;
        _logger = logger;
    }

    public async Task DispatchAsync(IDomainEvent domainEvent, CancellationToken ct)
    {
        switch (domainEvent)
        {
            case SslExpiryEvent e:
                await HandleSslExpiry(e, ct);
                break;
            // case ScanCompletedEvent e:
            //     await HandleScanCompleted(e, ct);
            //     break;
            default:
                _logger.LogWarning("No handler registered for event type {EventType}",
                    domainEvent.GetType().Name);
                break;
        }
    }

    // ── SSL Expiry ─────────────────────────────────────────────────────────────

    private async Task HandleSslExpiry(SslExpiryEvent e, CancellationToken ct)
    {
        // _logger.LogInformation("Dispatching SSL expiry alert for domain {DomainName}", e.DomainName);

        var prefs = await _prefs.GetByUserId(e.UserId, ct);
        var channels = ResolveChannels(prefs);

        // _logger.LogInformation(
        //     "Resolved {Count} channels for user {UserId}: {Channels}",
        //     channels.Count, e.UserId, string.Join(", ", channels));

        foreach (var channel in channels)
        {
            var alert = SslExpiryAlertFactory.Create(e, channel);
            await _alerts.AddAsync(alert, ct);


            try
            {
                await _alerts.SaveChangesAsync(ct);
                // _logger.LogInformation("SSL expiry alerts saved for domain {DomainName}", e.DomainName);
            }
            catch (DbUpdateException ex) when (IsUniqueConstraintViolation(ex))
            {
                // _logger.LogWarning(
                //     "Duplicate SSL expiry alert suppressed for domain {DomainName}", e.DomainName);
                _alerts.DetachUnsavedAlerts();
            }
        }
    }

    // ── Scan Completed ─────────────────────────────────────────────────────────

    // private async Task HandleScanCompleted(ScanCompletedEvent e, CancellationToken ct)
    // {
    //     _logger.LogInformation(
    //         "Dispatching scan completed alert for domain {DomainName}, score {Score}",
    //         e.DomainName, e.SecurityScore);

    //     var prefs = await _prefs.GetByUserId(e.UserId, ct);
    //     var channels = ResolveChannels(prefs);

    //     _logger.LogInformation(
    //         "Resolved {Count} channels for user {UserId}: {Channels}",
    //         channels.Count, e.UserId, string.Join(", ", channels));

    //     foreach (var channel in channels)
    //     {
    //         var alert = ScanCompletedAlertFactory.Create(e, channel);
    //         await _alerts.AddAsync(alert, ct);
    //     }

    //     try
    //     {
    //         await _alerts.SaveChangesAsync(ct);
    //         _logger.LogInformation(
    //             "Scan completed alerts saved for domain {DomainName}", e.DomainName);
    //     }
    //     catch (DbUpdateException ex) when (IsUniqueConstraintViolation(ex))
    //     {
    //         _logger.LogWarning(
    //             "Duplicate scan completed alert suppressed for domain {DomainName}", e.DomainName);
    //         _alerts.DetachUnsavedAlerts();
    //     }
    // }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private static List<AlertChannel> ResolveChannels(NotificationPreferences? prefs)
    {
        if (prefs is null)
            return [AlertChannel.Email]; // safe default

        var channels = new List<AlertChannel>();
        if (prefs.EmailAlerts) channels.Add(AlertChannel.Email);
        if (prefs.SlackAlerts) channels.Add(AlertChannel.Slack);
        if (prefs.PushNotifications) channels.Add(AlertChannel.Push);
        return channels;
    }

    private static bool IsUniqueConstraintViolation(DbUpdateException ex)
    {
        var inner = ex.InnerException;

        if (inner?.GetType().FullName == "Npgsql.PostgresException")
        {
            const string uniqueViolationSqlState = "23505";
            var sqlState = inner.GetType()
                .GetProperty("SqlState")?
                .GetValue(inner) as string;
            return sqlState == uniqueViolationSqlState;
        }

        return false;
    }
}