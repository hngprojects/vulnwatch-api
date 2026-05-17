using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Domain.Events;

namespace Application.Services;

// Application/Services/AlertDispatcher.cs
public class AlertDispatcher
{
    private readonly IAlertRepository _alerts;
    private readonly INotificationPreferencesRepository _prefs;

    public AlertDispatcher(
        IAlertRepository alerts,
        INotificationPreferencesRepository prefs)
    {
        _alerts = alerts;
        _prefs = prefs;
    }

    public async Task DispatchAsync(IDomainEvent domainEvent, CancellationToken ct)
    {
        switch (domainEvent)
        {
            case SslExpiryEvent e:
                await HandleSslExpiry(e, ct);
                break;
            case ScanCompletedEvent e:
                await HandleScanCompleted(e, ct);
                break;
            // case DomainStatusChangedEvent e:
            //     await HandleDomainStatusChanged(e, ct);
            //     break;
        }
    }

    private async Task HandleSslExpiry(SslExpiryEvent e, CancellationToken ct)
    {
        // Deduplicate — don't spam the same alert within 24h
        var alreadySent = await _alerts.HasRecentAlert(
            e.UserId, AlertType.SslExpiry, e.DomainId,
            TimeSpan.FromHours(24), ct);

        if (alreadySent) return;

        var prefs = await _prefs.GetByUserId(e.UserId, ct);
        var channels = ResolveChannels(prefs);

        foreach (var channel in channels)
        {
            var alert = AlertFactory.SslExpiry(e, channel);
            await _alerts.AddAsync(alert, ct);
        }

        await _alerts.SaveChangesAsync(ct);
    }

    private async Task HandleScanCompleted(ScanCompletedEvent e, CancellationToken ct)
    {
        var prefs = await _prefs.GetByUserId(e.UserId, ct);
        var channels = ResolveChannels(prefs);

        foreach (var channel in channels)
        {
            var alert = AlertFactory.ScanCompleted(e, channel);
            await _alerts.AddAsync(alert, ct);
        }

        await _alerts.SaveChangesAsync(ct);
    }

    // private async Task HandleDomainStatusChanged(DomainStatusChangedEvent e, CancellationToken ct)
    // {
    //     var prefs = await _prefs.GetByUserId(e.UserId, ct);
    //     var channels = ResolveChannels(prefs);

    //     foreach (var channel in channels)
    //     {
    //         var alert = AlertFactory.DomainStatusChanged(e, channel);
    //         await _alerts.AddAsync(alert, ct);
    //     }

    //     await _alerts.SaveChangesAsync(ct);
    // }

    private static List<AlertChannel> ResolveChannels(NotificationPreferences? prefs)
    {
        if (prefs is null) return [AlertChannel.Email]; // default

        var channels = new List<AlertChannel>();
        if (prefs.EmailAlerts) channels.Add(AlertChannel.Email);
        if (prefs.SlackAlerts) channels.Add(AlertChannel.Slack);
        if (prefs.PushNotifications) channels.Add(AlertChannel.Push);
        return channels;
    }
}