using Application.Features.Monitoring.DTOs;
using Application.Interfaces;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using MediatR;

namespace Application.Features.Monitoring;

public record ToggleMonitoringCommand(Guid DomainId, bool Enable)
    : IRequest<Result<MonitoringSettingsDto>>;

public class ToggleMonitoringHandler(
    IDomainRepository domains,
    IDomainSettingsRepository settingsRepo,
    ICurrentUser currentUser)
    : IRequestHandler<ToggleMonitoringCommand, Result<MonitoringSettingsDto>>
{
    public async Task<Result<MonitoringSettingsDto>> Handle(
        ToggleMonitoringCommand cmd, CancellationToken ct)
    {
        var domain = await domains.FindUserDomainById(
            currentUser.UserId, cmd.DomainId, ct);

        if (domain is null)
            return Result<MonitoringSettingsDto>.Failure(
                Error.NotFound("Domain not found."));

        var existing = await settingsRepo.GetByDomainId(cmd.DomainId, ct);

        if (existing is null)
        {
            // First toggle — seed defaults then apply
            existing = DomainSettings.CreateDefault(cmd.DomainId);
            await settingsRepo.AddAsync(existing, ct);
        }

        if (cmd.Enable)
            existing.Enable();
        else
            existing.Disable();

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