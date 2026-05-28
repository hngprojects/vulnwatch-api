using Domain.Enums;

namespace Application.Interfaces;

public interface IChatServiceFactory
{
    IChatService Resolve();
}
public interface IChatService
{
    Task<string> Chat(
        string systemPrompt,
        List<(ChatMessageRole Role, string Content)> history,
        string userMessage,
        CancellationToken ct);

    IAsyncEnumerable<string> Stream(
        string systemPrompt,
        List<(ChatMessageRole Role, string Content)> history,
        string userMessage,
        CancellationToken ct);
}