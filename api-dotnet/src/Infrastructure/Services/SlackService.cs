using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Application.Features.Integrations.DTOs;
using Application.Interfaces;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace Infrastructure.Services;

public class SlackService(
    IHttpClientFactory httpClientFactory,
    IConfiguration config,
    ILogger<SlackService> logger) : ISlackService
{
    public async Task<SlackOAuthResult> ExchangeCodeAsync(string code, CancellationToken ct)
    {
        var clientId = config["Slack:ClientId"]!;
        var clientSecret = config["Slack:ClientSecret"]!;
        var redirectUri = config["Slack:RedirectUri"]!;

        var client = httpClientFactory.CreateClient("slack");

        var formFields = new Dictionary<string, string>
        {
            ["code"] = code,
            ["client_id"] = clientId,
            ["client_secret"] = clientSecret,
            ["redirect_uri"] = redirectUri,
        };

        var form = new FormUrlEncodedContent(formFields);

        HttpResponseMessage response;
        try
        {
            response = await client.PostAsync(
                "https://slack.com/api/oauth.v2.access", form, ct);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Slack OAuth HTTP request failed");
            throw;
        }

        var rawBody = await response.Content.ReadAsStringAsync(ct);


        if (!response.IsSuccessStatusCode)
        {
            response.EnsureSuccessStatusCode();
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

            throw new InvalidOperationException($"Slack OAuth failed: {error}");
        }

        var team = json.GetProperty("team");
        var channel = json.GetProperty("incoming_webhook");

        var teamId = team.GetProperty("id").GetString()!;
        var teamName = team.GetProperty("name").GetString()!;
        var channelId = channel.GetProperty("channel_id").GetString()!;
        var channelName = channel.GetProperty("channel").GetString()!;

        return new SlackOAuthResult(
            TeamId: teamId,
            TeamName: teamName,
            ChannelId: channelId,
            ChannelName: channelName,
            BotAccessToken: json.GetProperty("access_token").GetString()!);
    }

    public async Task SendMessageAsync(
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
}