namespace Application.Features.Integrations.DTOs;

public record SlackStatusSummary(
    bool   Connected,
    string TeamName,
    string ChannelName);