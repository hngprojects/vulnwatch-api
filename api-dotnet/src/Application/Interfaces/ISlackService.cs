using Application.Features.Integrations.DTOs;

namespace Application.Interfaces;

public interface ISlackService
{
    Task<SlackOAuthResult> ExchangeCodeAsync(string code, CancellationToken ct);
    Task SendMessageAsync(string botToken, string channelId, string text, object? blocks = null, CancellationToken ct = default);
}