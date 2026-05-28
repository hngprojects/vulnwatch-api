using Application.Features.Integrations.Slack.DTOs;


namespace Application.Interfaces;

public interface ISlackOAuthService
{
    Task<SlackTokenResponse> ExchangeCodeAsync(string code, CancellationToken ct);
}
