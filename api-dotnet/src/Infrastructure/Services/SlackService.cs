using System.Net.Http.Json;
using System.Text.Json.Serialization;
using Application.Features.Integrations.Slack.DTOs;
using Application.Interfaces;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace Infrastructure.Services;

public sealed class SlackOAuthService(
    IConfiguration config,
    ILogger<SlackOAuthService> logger) : ISlackOAuthService
{
    public async Task<SlackTokenResponse> ExchangeCodeAsync(
        string code, CancellationToken ct)
    {
        var clientId = config["Slack:ClientId"]!;
        var clientSecret = config["Slack:ClientSecret"]!;
        var redirectUri = config["Slack:RedirectUri"]!;

        using var http = new HttpClient();

        var response = await http.PostAsync(
            "https://slack.com/api/oauth.v2.access",
            new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"]     = clientId,
                ["client_secret"] = clientSecret,
                ["code"]          = code,
                ["redirect_uri"]  = redirectUri,
            }),
            ct);

        if (!response.IsSuccessStatusCode)
        {
            logger.LogWarning("Slack token exchange HTTP error: {Status}",
                response.StatusCode);
            return new SlackTokenResponse(false, null, null, null,
                $"HTTP {response.StatusCode}");
        }

        var payload = await response.Content
            .ReadFromJsonAsync<SlackOAuthPayload>(cancellationToken: ct);

        if (payload is null || !payload.Ok)
            return new SlackTokenResponse(false, null, null, null,
                payload?.Error ?? "empty response");

        return new SlackTokenResponse(
            Ok: true,
            AccessToken: payload.AccessToken,
            TeamId: payload.Team?.Id,
            TeamName: payload.Team?.Name,
            Error: null);
    }
}

// Minimal shape matching Slack's oauth.v2.access response
file sealed class SlackOAuthPayload
{
    [JsonPropertyName("ok")]
    public bool Ok { get; init; }

    [JsonPropertyName("error")]
    public string? Error { get; init; }

    [JsonPropertyName("access_token")]
    public string? AccessToken { get; init; }

    [JsonPropertyName("team")]
    public SlackTeam? Team { get; init; }
}

file sealed class SlackTeam
{
    [JsonPropertyName("id")]
    public string? Id { get; init; }

    [JsonPropertyName("name")]
    public string? Name { get; init; }
}