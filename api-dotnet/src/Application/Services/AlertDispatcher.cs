using Application.Interfaces;
using Domain.Entities;
using Domain.Enums;
using Domain.Events;
using Microsoft.EntityFrameworkCore;
namespace Application.Services;

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
        }
    }

    private async Task HandleSslExpiry(SslExpiryEvent e, CancellationToken ct)
    {
        // REMOVED: HasRecentAlert pre-check — it was a non-atomic TOCTOU guard.
        // Deduplication is now enforced by the DB-level unique index on
        // (UserId, AlertType, DomainId, DeduplicationKey).
        // See migration: AddAlertDeduplicationIndex.
        // We attempt the insert unconditionally and treat a unique-constraint
        // violation as "already sent" — a safe no-op.

        var prefs = await _prefs.GetByUserId(e.UserId, ct);
        var channels = ResolveChannels(prefs);

        foreach (var channel in channels)
        {
            var alert = AlertFactory.SslExpiry(e, channel);
            await _alerts.AddAsync(alert, ct);
        }

        try
        {
            await _alerts.SaveChangesAsync(ct);
        }
        catch (DbUpdateException ex) when (IsUniqueConstraintViolation(ex))
        {
            // A concurrent dispatcher already inserted the same alert window.
            // Detach tracked-but-unsaved entities so the DbContext stays clean,
            // then swallow — this outcome is correct, not an error.
            _alerts.DetachUnsavedAlerts();
        }
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