using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Application.Features.Integrations.Slack.DTOs;
using Application.Interfaces;
using Domain.Common;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace Infrastructure.Services;

public sealed class SlackService(
    IHttpClientFactory httpClientFactory,
    IConfiguration config,
    ILogger<SlackService> logger) : ISlackService
{
    public async Task<Result<SlackToken>> ExchangeCode(
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
                ["client_id"] = clientId,
                ["client_secret"] = clientSecret,
                ["code"] = code,
                ["redirect_uri"] = redirectUri,
            }),
            ct);

        var rawBody = await response.Content.ReadAsStringAsync(ct);

        // // Log the full raw response before any processing
        // logger.LogInformation("[Slack OAuth] Raw token exchange response: {Body}", rawBody);

        if (!response.IsSuccessStatusCode)
        {
            // logger.LogWarning("Slack token exchange HTTP error: {Status}",
            //     response.StatusCode);
            return Result<SlackToken>.Failure(Error.Internal($"HTTP {response.StatusCode}"));
        }

        JsonElement json;
        try
        {
            json = JsonSerializer.Deserialize<JsonElement>(rawBody);
        }
        catch (JsonException ex)
        {
            logger.LogError(ex, "Failed to deserialize Slack OAuth response — RawBody: {Body}", rawBody);
            throw;
        }

        var ok = json.GetProperty("ok").GetBoolean();

        if (!ok)
        {
            var error = json.TryGetProperty("error", out var e) ? e.GetString() : "unknown";

            return Result<SlackToken>.Failure(Error.Internal($"Slack OAuth failed: {error}"));
        }

        var team = json.GetProperty("team");
        var webhook = json.GetProperty("incoming_webhook");
        var authedUser = json.GetProperty("authed_user");

        return Result<SlackToken>.Success(
                    SlackToken.Success(
            json.GetProperty("app_id").GetString()!,
            new SlackAuthedUser(authedUser.GetProperty("id").GetString()!),
            json.GetProperty("scope").GetString()!,
            json.GetProperty("token_type").GetString()!,
            json.GetProperty("access_token").GetString()!,
            json.GetProperty("bot_user_id").GetString()!,
            new SlackTeam(
                team.GetProperty("id").GetString()!,
                team.GetProperty("name").GetString()!
            ),
            json.GetProperty("is_enterprise_install").GetBoolean(),
            new SlackIncomingWebhook(
                Channel: webhook.GetProperty("channel").GetString()!,
                ChannelId: webhook.GetProperty("channel_id").GetString()!,
                ConfigurationUrl: webhook.GetProperty("configuration_url").GetString()!,
                Url: webhook.GetProperty("url").GetString()!
            )
        ));
    }
    public async Task SendMessage(
        string botToken, string channelId, string text,
        object? blocks = null, CancellationToken ct = default)
    {
        var client = httpClientFactory.CreateClient("slack");
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", botToken);

        object payload = blocks is not null
            ? new { channel = channelId, text, blocks }
            : new { channel = channelId, text };

        var content = new StringContent(
            JsonSerializer.Serialize(payload, new JsonSerializerOptions
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase
            }),
            Encoding.UTF8,
            "application/json");

        var response = await client.PostAsync(
            "https://slack.com/api/chat.postMessage", content, ct);

        response.EnsureSuccessStatusCode();

        var json = await response.Content.ReadFromJsonAsync<JsonElement>(
            cancellationToken: ct);

        if (!json.GetProperty("ok").GetBoolean())
            logger.LogWarning("Slack message failed: {Error}",
                json.GetProperty("error").GetString());
    }

    public async Task SendMessageViaWebhookUrl(
    string webhookUrl, string text,
    object? blocks = null, CancellationToken ct = default)
    {
        var client = httpClientFactory.CreateClient("slack");

        object payload = blocks is not null
            ? new { text, blocks }
            : new { text };

        var content = new StringContent(
            JsonSerializer.Serialize(payload, new JsonSerializerOptions
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase
            }),
            Encoding.UTF8,
            "application/json");

        var response = await client.PostAsync(webhookUrl, content, ct);
        response.EnsureSuccessStatusCode();
    }

}
