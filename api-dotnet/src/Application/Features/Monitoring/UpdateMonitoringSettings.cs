using Application.Features.Monitoring.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;

namespace Application.Features.Monitoring;

public record UpdateMonitoringSettingsCommand(
    Guid DomainId,
    bool MonitoringEnabled,
    ScanFrequency ScanFrequency,
    List<int> SslAlertThresholds,
    List<AlertChannel> NotificationChannels)
    : IRequest<Result<MonitoringSettingsDto>>;

public class UpdateMonitoringSettingsHandler(
    IDomainRepository domains,
    IDomainSettingsRepository settingsRepo,
    ICurrentUser currentUser)
    : IRequestHandler<UpdateMonitoringSettingsCommand, Result<MonitoringSettingsDto>>
{
    public async Task<Result<MonitoringSettingsDto>> Handle(
        UpdateMonitoringSettingsCommand cmd, CancellationToken ct)
    {
        var domain = await domains.FindUserDomainById(
            currentUser.UserId, cmd.DomainId, ct);

        if (domain is null)
            return Result<MonitoringSettingsDto>.Failure(
                Error.NotFound("Domain not found."));

        if (domain.VerificationStatus != VerificationStatus.Verified)
            return Result<MonitoringSettingsDto>.Failure(
                Error.Forbidden(
                    "Monitoring can only be configured for verified domains."));

        var existing = await settingsRepo.GetByDomainId(cmd.DomainId, ct);

        if (existing is null)
        {
            existing = DomainSettings.CreateDefault(cmd.DomainId);
            await settingsRepo.AddAsync(existing, ct);
        }

        try
        {
            var combined = cmd.NotificationChannels
                .Aggregate(AlertChannel.None, (acc, c) => acc | c);

            existing.UpdateSettings(
                cmd.MonitoringEnabled,
                cmd.ScanFrequency,
                cmd.SslAlertThresholds,
                combined);
        }
        catch (ArgumentException ex)
        {
            return Result<MonitoringSettingsDto>.Failure(
                Error.Validation(ex.Message));
        }

        await settingsRepo.SaveChangesAsync(ct);

        return Result<MonitoringSettingsDto>.Success(
            MonitoringSettingsMappings.ToDto(existing, domain.DomainName));
    }
}


// ── Shared projection ─────────────────────────────────────────────────────────

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