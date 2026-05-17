using Domain.Entities;
using Domain.Enums;
using Domain.Events;

namespace Application.Services;

// Builds the outbox row
public static class AlertFactory
{
    public static Alert SslExpiry(SslExpiryEvent e, AlertChannel channel)
    {
        var severity = e.DaysRemaining <= 7
            ? AlertSeverity.Critical
            : AlertSeverity.Warning;

        return Alert.Create(
            userId: e.UserId,
            type: AlertType.SslExpiry,
            channel: channel,
            severity: severity,
            subject: $"SSL certificate for {e.DomainName} expires in {e.DaysRemaining} days",
            body: BuildSslExpiryBody(e),
            domainId: e.DomainId);
    }

    public static Alert ScanCompleted(ScanCompletedEvent e, AlertChannel channel)
    {
        var hasCritical = e.FindingSeverities.Any(s => s == FindingSeverity.Critical);
        var severity = hasCritical ? AlertSeverity.Critical : AlertSeverity.Info;

        return Alert.Create(
            userId: e.UserId,
            type: AlertType.ScanCompleted,
            channel: channel,
            severity: severity,
            subject: $"Scan complete for {e.DomainName} — Score: {e.SecurityScore}/100",
            body: BuildScanCompletedBody(e),
            domainId: e.DomainId);
    }

    // public static Alert DomainStatusChanged(DomainStatusChangedEvent e, AlertChannel channel) =>
    //     Alert.Create(
    //         userId: e.UserId,
    //         type: AlertType.DomainStatusChanged,
    //         channel: channel,
    //         severity: AlertSeverity.Info,
    //         subject: $"Domain {e.DomainName} status changed to {e.NewStatus}",
    //         body: BuildStatusChangedBody(e),
    //         domainId: e.DomainId);

    private static string BuildSslExpiryBody(SslExpiryEvent e) => $"""
        <p>Your SSL certificate for <strong>{e.DomainName}</strong> 
        expires on <strong>{e.ExpiresAt:MMMM dd, yyyy}</strong> 
        ({e.DaysRemaining} days remaining).</p>
        <p>Renew it before expiry to avoid security warnings for your users.</p>
        """;

    private static string BuildScanCompletedBody(ScanCompletedEvent e) => $"""
        <p>Scan completed for <strong>{e.DomainName}</strong>.</p>
        <p>Security score: <strong>{e.SecurityScore}/100</strong></p>
        <p>Critical findings: {e.FindingSeverities.Count(s => s == FindingSeverity.Critical)}</p>
        """;

    // private static string BuildStatusChangedBody(DomainStatusChangedEvent e) => $"""
    //     <p>Domain <strong>{e.DomainName}</strong> status changed 
    //     from <strong>{e.OldStatus}</strong> to <strong>{e.NewStatus}</strong>.</p>
    //     """;
}