using Application.Interfaces;
using Domain.Enums;
using Microsoft.Extensions.Logging;
using System.Runtime.CompilerServices;
using System.Text.Json;

namespace Infrastructure.Services.Chat;

public abstract class ChatServiceBase : IChatService
{
    protected abstract ILogger Logger { get; }

    // Each provider builds its own request message
    protected abstract HttpRequestMessage BuildRequest(
        string systemPrompt,
        List<(ChatMessageRole Role, string Content)> history,
        bool stream);

    // Each provider knows how to extract text from its own SSE line
    protected abstract string? ParseSseChunk(JsonElement root);

    // Each provider knows what its [DONE] sentinel looks like
    protected virtual string DoneSentinel => "[DONE]";

    protected abstract HttpClient Http { get; }

    // ── Buffered ──────────────────────────────────────────────────────────────

    public async Task<string> Chat(
        string systemPrompt,
        List<(ChatMessageRole Role, string Content)> history,
        string userMessage,
        CancellationToken ct)
    {
        var chunks = new System.Text.StringBuilder();
        await foreach (var chunk in Stream(systemPrompt, history, userMessage, ct))
            chunks.Append(chunk);
        return chunks.ToString();
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    public async IAsyncEnumerable<string> Stream(
        string systemPrompt,
        List<(ChatMessageRole Role, string Content)> history,
        string userMessage,
        [EnumeratorCancellation] CancellationToken ct)
    {
        HttpResponseMessage response;

        try
        {
            var request = BuildRequest(systemPrompt, history, stream: true);
            response = await Http.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, ct);
            response.EnsureSuccessStatusCode();
        }
        catch (Exception ex)
        {
            Logger.LogError(ex, "[{Provider}] Streaming request failed", GetType().Name);
            yield break;
        }

        using var httpStream = await response.Content.ReadAsStreamAsync(ct);
        using var reader = new System.IO.StreamReader(httpStream);

        while (!reader.EndOfStream && !ct.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(ct);
            if (string.IsNullOrWhiteSpace(line)) continue;
            if (!line.StartsWith("data: ")) continue;

            var data = line["data: ".Length..];
            if (data == DoneSentinel) yield break;

            string? text = null;
            try
            {
                var doc = JsonDocument.Parse(data);
                text = ParseSseChunk(doc.RootElement);
            }
            catch (JsonException ex)
            {
                Logger.LogWarning(ex, "[{Provider}] Failed to parse SSE chunk: {Line}",
                    GetType().Name, line);
                continue;
            }

            if (!string.IsNullOrEmpty(text))
                yield return text;
        }
    }
}