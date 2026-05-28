namespace Application.Features.Integrations.Slack.DTOs;

public record SlackTokenResponse(
    bool Ok,
    string? AccessToken,
    string? TeamId,
    string? TeamName,
    string? Error);