
namespace Application.Features.Profile.DTOs;

public record NotificationPreferencesDto(
    bool EmailAlerts,
    bool SlackAlerts,
    bool PushNotifications
);