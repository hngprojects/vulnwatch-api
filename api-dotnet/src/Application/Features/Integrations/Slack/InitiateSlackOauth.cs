
using Application.Interfaces;
using Domain.Common;
using MediatR;
using Microsoft.Extensions.Configuration;

namespace Application.Features.Integrations;

public record InitiateSlackOAuthCommand : IRequest<Result<SlackAuthUrlResponse>>;

public record SlackAuthUrlResponse(string AuthorizationUrl);

public class InitiateSlackOAuthHandler(
    ICurrentUser currentUser,
    IRedisService stateStore,
    IConfiguration config)
    : IRequestHandler<InitiateSlackOAuthCommand, Result<SlackAuthUrlResponse>>
{
    public async Task<Result<SlackAuthUrlResponse>> Handle(
        InitiateSlackOAuthCommand _, CancellationToken ct)
    {
        var clientId = config["Slack:ClientId"];
        var redirectUri = config["Slack:RedirectUri"];
        var scopes = config["Slack:Scopes"] ?? "incoming-webhook,chat:write";

        if (string.IsNullOrWhiteSpace(clientId) || string.IsNullOrWhiteSpace(redirectUri))
            return Result<SlackAuthUrlResponse>.Failure(
                Error.Internal("Slack integration is not configured."));

        // Validate current user is properly authenticated
        if (currentUser.UserId == Guid.Empty || !currentUser.IsAuthenticated)
            return Result<SlackAuthUrlResponse>.Failure(
                Error.Unauthorized("User is not authenticated."));

        var state = GenerateState();
        await stateStore.SaveSlackState(state, currentUser.UserId, ct);

        var url = $"https://slack.com/oauth/v2/authorize" +
                  $"?client_id={Uri.EscapeDataString(clientId)}" +
                  $"&scope={Uri.EscapeDataString(scopes)}" +
                  $"&redirect_uri={Uri.EscapeDataString(redirectUri)}" +
                  $"&state={Uri.EscapeDataString(state)}";

        return Result<SlackAuthUrlResponse>.Success(new SlackAuthUrlResponse(url));
    }

    private static string GenerateState()
    {
        var bytes = new byte[32];
        System.Security.Cryptography.RandomNumberGenerator.Fill(bytes);
        return Convert.ToBase64String(bytes)
            .Replace("+", "-").Replace("/", "_").TrimEnd('=');
    }
}