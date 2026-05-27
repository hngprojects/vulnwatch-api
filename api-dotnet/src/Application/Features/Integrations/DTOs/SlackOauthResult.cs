namespace Application.Features.Integrations.DTOs;

public record SlackOAuthResult(
    string TeamId,
    string TeamName,
    string ChannelId,
    string ChannelName,
    string BotAccessToken);