using Application.Features.Monitoring.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;

namespace Application.Features.Monitoring;

public record GetDomainSettingsQuery(Guid DomainId)
    : IRequest<Result<MonitoringSettingsDto>>;

public class GetDomainSettingsHandler(
    IDomainRepository domains,
    IDomainSettingsRepository settings,
    ICurrentUser currentUser)
    : IRequestHandler<GetDomainSettingsQuery, Result<MonitoringSettingsDto>>
{
    public async Task<Result<MonitoringSettingsDto>> Handle(
        GetDomainSettingsQuery query, CancellationToken ct)
    {
        var domain = await domains.FindUserDomainById(
            currentUser.UserId, query.DomainId, ct);

        if (domain is null)
            return Result<MonitoringSettingsDto>.Failure(
                Error.NotFound("Domain not found."));

        var s = await settings.GetByDomainId(query.DomainId, ct)
             ?? DomainSettings.CreateDefault(query.DomainId);

        return Result<MonitoringSettingsDto>.Success(
            MonitoringSettingsMappings.ToDto(s, domain.DomainName));
    }
};

file static class MonitoringSettingsMappings
{
    public static MonitoringSettingsDto ToDto(
        DomainSettings s, string domainName) => new(
            DomainId:             s.DomainId,
            DomainName:           domainName,
            MonitoringEnabled:    s.MonitoringEnabled,
            ScanFrequency:        s.ScanFrequency,
            NextScanIn:           BuildNextScanLabel(s.NextScheduledAt),
            LastMonitoredAt:      s.LastMonitoredAt,
            NextScheduledAt:      s.NextScheduledAt,
            SslAlertThresholds:   s.GetSslAlertThresholds(),
            NotificationChannels: Enum.GetValues<AlertChannel>()
                .Where(c => c != AlertChannel.None && s.NotificationChannel.HasFlag(c))
                .Select(c => c.ToString())
                .ToList());

    private static string BuildNextScanLabel(DateTime? next)
    {
        if (next is null) return "Not scheduled";
        var diff = next.Value - DateTime.UtcNow;
        if (diff <= TimeSpan.Zero) return "Due now";
        if (diff.TotalMinutes < 60)
            return $"In {(int)diff.TotalMinutes}m";
        if (diff.TotalHours < 24)
            return $"In {(int)diff.TotalHours}h {diff.Minutes}m";
        return $"In {(int)diff.TotalDays}d";
    }
}