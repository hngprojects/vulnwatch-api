using Application.Features.Monitoring.DTOs;
using Domain.Entities;
using Domain.Enums;

namespace Application.Features.Monitoring.Mappings;

internal static class MonitoringSettingsMappings
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


// public class MonitoringSettingsMapping : Profile
// {
//     public MonitoringSettingsMapping()
//     {
//         CreateMap<DomainSettings, MonitoringSettingsDto>()
//             .ForMember(dest => dest.DomainName, opt => opt.MapFrom(src => src.Domain.DomainName))
//             .ForMember(dest => dest.NextScanIn, opt => opt.Ignore()) // handled in handler
//             .ForMember(dest => dest.SslAlertThresholds, opt => opt.MapFrom(src =>
//                 src.SslAlertThresholds.Split(',', StringSplitOptions.RemoveEmptyEntries)
//                     .Select(int.Parse)
//                     .ToArray()))
//             .ForMember(dest => dest.NotificationChannels, opt => opt.MapFrom(src =>
//                 src.NotificationChannel.Split(',', StringSplitOptions.RemoveEmptyEntries)
//                     .ToArray()));
//     }
// }